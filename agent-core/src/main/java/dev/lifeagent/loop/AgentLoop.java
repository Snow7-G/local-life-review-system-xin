package dev.lifeagent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.lifeagent.event.AgentEvent;
import dev.lifeagent.event.AnswerEvent;
import dev.lifeagent.event.PlanEvent;
import dev.lifeagent.event.ToolCallEvent;
import dev.lifeagent.event.ToolResultEvent;
import dev.lifeagent.hook.HookChain;
import dev.lifeagent.hook.StepDecision;
import dev.lifeagent.hook.TurnContext;
import dev.lifeagent.llm.ContentBlock;
import dev.lifeagent.llm.LlmClient;
import dev.lifeagent.llm.LlmException;
import dev.lifeagent.llm.LlmRequest;
import dev.lifeagent.llm.LlmResponse;
import dev.lifeagent.llm.LlmStreamEvent;
import dev.lifeagent.llm.Message;
import dev.lifeagent.llm.MessageDelta;
import dev.lifeagent.llm.Role;
import dev.lifeagent.llm.StopReason;
import dev.lifeagent.llm.TextBlock;
import dev.lifeagent.llm.TextDelta;
import dev.lifeagent.llm.TokenUsage;
import dev.lifeagent.llm.ToolResultBlock;
import dev.lifeagent.llm.ToolUseArgumentsDelta;
import dev.lifeagent.llm.ToolUseBlock;
import dev.lifeagent.llm.ToolUseEnd;
import dev.lifeagent.llm.ToolUseStart;
import dev.lifeagent.tool.Permission;
import dev.lifeagent.tool.ToolDefinition;
import dev.lifeagent.tool.ToolErrorKind;
import dev.lifeagent.tool.ToolFailure;
import dev.lifeagent.tool.ToolOutcome;
import dev.lifeagent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * The ReAct loop: build context, ask the model, gate and run the tools it asked for, feed the results
 * back, repeat until the model answers or a ceiling is hit (manual §5.1).
 *
 * <p>It depends on the {@code llm}, {@code tool}, {@code hook} and {@code event} packages and on
 * nothing else - no Spring, no Redis, and no concrete adapter is ever constructed here. The
 * {@link LlmClient} is injected, which is also how the tests drive it with a fake model.</p>
 *
 * <p>What this class guarantees:</p>
 * <ul>
 *   <li><strong>Ceilings.</strong> A turn issues at most {@code budget.maxToolCalls()} tool calls and
 *       accumulates at most {@code budget.maxTokens()} tokens. Reaching either one appends a wrap-up
 *       instruction, asks the model once more <em>without</em> advertising tools, and stops with
 *       {@link LoopStopReason#BUDGET_EXHAUSTED} (or {@code MAX_STEPS} when the tool-call ceiling is
 *       what ran out) while keeping whatever text the model produced.</li>
 *   <li><strong>Parallelism.</strong> Every {@link Permission#READ} call of a step runs on virtual
 *       threads ({@code Executors.newVirtualThreadPerTaskExecutor()}), all submitted before the first
 *       result is awaited; calls that are not {@code READ} run serially in declaration order. Events
 *       and history are always written in request order, never completion order.</li>
 *   <li><strong>Errors as data.</strong> A failed tool never throws out of the loop; it is shortened
 *       to one line of text and handed back so the model can retry or route around it.</li>
 *   <li><strong>Circuit breaker.</strong> Three consecutive failures of {@code (sessionId, toolName)}
 *       stop that tool from being entered again for that session; one success resets the count.</li>
 *   <li><strong>Permission.</strong> {@link Permission#READ} runs directly; {@code WRITE} and
 *       {@code HIGH_RISK} go through an injected {@link PermissionGate} whose default refuses.</li>
 *   <li><strong>Hooks.</strong> {@code pre-model}/{@code post-model}/{@code pre-tool}/{@code post-tool}
 *       run in registration order. A hook that throws is treated as a block, and a block at the
 *       pre-model point means the model is not called <em>at all</em> for that step.</li>
 *   <li><strong>Suspension.</strong> A deferring gate stores the session, returns
 *       {@link LoopStopReason#WAITING_USER} without blocking a thread, and {@link #resume} restarts
 *       the interrupted step from the stored state.</li>
 * </ul>
 *
 * <p>Stateless apart from the shared circuit breaker and the injected collaborators: every turn's
 * state lives in its {@link LoopSession}, so one instance can serve concurrent sessions.</p>
 */
public final class AgentLoop {

    private static final String WRAP_UP_NOTE =
            "已达到本次任务的资源上限，请不要再调用任何工具，直接用现有信息给出最终答复。";

    /** How many times a step's model call may be retried when the stream yields an unusable call. */
    private static final int MAX_CHAT_ATTEMPTS = 2;

    private final LlmClient llmClient;
    private final ToolRegistry registry;
    private final HookChain hookChain;
    private final PermissionGate permissionGate;
    private final SessionStore sessionStore;
    private final ToolCircuitBreaker circuitBreaker;
    private final Consumer<AgentEvent> eventSink;

    /**
     * @param llmClient      the model client to call
     * @param registry       the tools the model may call
     * @param hookChain      the four hook points
     * @param permissionGate gates {@code WRITE}/{@code HIGH_RISK} calls; use
     *                       {@link PermissionGate#denyAll()} to fail closed
     */
    public AgentLoop(
            LlmClient llmClient, ToolRegistry registry, HookChain hookChain, PermissionGate permissionGate) {
        this(llmClient, registry, hookChain, permissionGate, new InMemorySessionStore(),
                new ToolCircuitBreaker(ToolCircuitBreaker.DEFAULT_FAILURE_THRESHOLD), event -> {
                });
    }

    /**
     * @param llmClient      the model client to call
     * @param registry       the tools the model may call
     * @param hookChain      the four hook points
     * @param permissionGate the permission gate
     * @param sessionStore   where suspended sessions are stored
     * @param circuitBreaker shared breaker; one per process is the normal wiring
     * @param eventSink      where the six event types are pushed; may be a no-op
     */
    public AgentLoop(
            LlmClient llmClient,
            ToolRegistry registry,
            HookChain hookChain,
            PermissionGate permissionGate,
            SessionStore sessionStore,
            ToolCircuitBreaker circuitBreaker,
            Consumer<AgentEvent> eventSink) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.hookChain = Objects.requireNonNull(hookChain, "hookChain");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    /**
     * @param llmClient      the model client to call
     * @param registry       the tools the model may call
     * @param hookChain      the four hook points
     * @param permissionGate the permission gate
     * @return a loop with an in-memory session store and a fresh breaker
     */
    public static AgentLoop create(
            LlmClient llmClient, ToolRegistry registry, HookChain hookChain, PermissionGate permissionGate) {
        return new AgentLoop(llmClient, registry, hookChain, permissionGate);
    }

    /** @return the circuit breaker this loop consults. */
    public ToolCircuitBreaker breaker() {
        return circuitBreaker;
    }

    // ------------------------------------------------------------------ run

    /**
     * Starts a fresh turn.
     *
     * @param config the turn's inputs and budget
     * @return how the turn ended
     */
    public LoopResult run(LoopConfig config) {
        return run(config, null);
    }

    /**
     * Runs one turn, optionally continuing a session.
     *
     * @param config  the turn's inputs and budget
     * @param session a session to continue, or {@code null} to start with an empty history
     * @return how the turn ended
     */
    public LoopResult run(LoopConfig config, LoopSession session) {
        Objects.requireNonNull(config, "config");
        LoopSession state = session == null
                ? new LoopSession(config.sessionId(), List.of(), config.budget())
                : requireSameSession(config, session);
        state.applyBudget(config.budget());
        if (state.status() != LoopSession.Status.RUNNING) {
            throw new IllegalStateException(
                    "session must be resumable before run(): status=" + state.status());
        }
        state.append(new Message(Role.USER, List.of(new TextBlock(config.userMessage()))));
        return drive(config, state);
    }

    /**
     * Resumes a session suspended by a deferring {@link PermissionGate}: the user's answer becomes the
     * interrupted call's tool result, the remaining calls of that step finish, and the loop carries
     * on from the model.
     *
     * @param session   the suspended session
     * @param userReply the user's answer
     * @return how the turn ended
     */
    public LoopResult resume(LoopSession session, String userReply) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(userReply, "userReply");
        if (!session.isWaitingForUser()) {
            throw new IllegalStateException("session is not waiting for a user reply");
        }
        PendingPermission pending = session.pendingPermission();
        List<ToolCallRequest> suspended = session.suspendedCalls();
        if (suspended.isEmpty()) {
            throw new IllegalStateException("suspended session lost its interrupted step");
        }
        session.markResumed();
        LoopConfig config = new LoopConfig(session.sessionId(), null, null, userReply, session.budget());
        // Re-enter the interrupted step at the call that deferred. Hooks and the gate see the user's
        // reply as the step's untrusted text, which is how a confirmation flips a Defer into an
        // Allow; the assistant tool_use message is already in the history from before the pause.
        StepOutcome step = runStep(config, session, contextFor(session, config), suspended, 0, false);
        if (step.suspended()) {
            return suspend(session, step);
        }
        if (step.exhausted()) {
            return exhaust(config, session, session.toolCallsUsed() >= config.budget().maxToolCalls()
                    ? LoopStopReason.MAX_STEPS : LoopStopReason.BUDGET_EXHAUSTED);
        }
        return drive(config, session);
    }

    // ------------------------------------------------------------- main loop

    private LoopResult drive(LoopConfig config, LoopSession session) {
        boolean planEmitted = false;
        while (true) {
            TurnContext context = contextFor(session, config);

            if (session.tokensUsed() >= config.budget().maxTokens()) {
                return exhaust(config, session, LoopStopReason.BUDGET_EXHAUSTED);
            }

            StepDecision preModel = hookChain.beforeModel(context);
            if (preModel.blocked()) {
                return blocked(session, preModel.reason());
            }
            String systemPrompt = withNote(config.systemPrompt(), preModel.noteOrEmpty());

            ChatResult chat = chat(config, session, systemPrompt);
            if (chat.failure() != null) {
                return fail(session, chat.failure());
            }
            if (chat.budgetExhausted()) {
                return exhaust(config, session, LoopStopReason.BUDGET_EXHAUSTED);
            }
            if (chat.turn().toolCalls().isEmpty()) {
                return answered(session, chat.turn().text());
            }

            List<ToolCallRequest> requests = chat.turn().toolCalls().stream()
                    .map(use -> new ToolCallRequest(use.id(), use.name(), use.arguments()))
                    .toList();

            StepDecision postModel = hookChain.afterModel(context, chat.response());
            if (postModel.blocked()) {
                appendAssistantToolUses(session, requests);
                appendBlockedResults(session, requests, postModel.reason());
                return blocked(session, postModel.reason());
            }

            if (!planEmitted) {
                emitPlan(requests);
                planEmitted = true;
            }

            StepOutcome step = runStep(config, session, context, requests, 0, true);
            if (step.suspended()) {
                return suspend(session, step);
            }
            if (step.exhausted()) {
                return exhaust(config, session, session.toolCallsUsed() >= config.budget().maxToolCalls()
                        ? LoopStopReason.MAX_STEPS : LoopStopReason.BUDGET_EXHAUSTED);
            }
        }
    }

    private LoopResult suspend(LoopSession session, StepOutcome step) {
        ToolCallRequest pending = step.pending();
        session.suspend(new PendingPermission(pending, permissionOf(pending), step.deferReason()),
                step.remaining());
        sessionStore.save(session);
        return new LoopResult(session.sessionId(), LoopStopReason.WAITING_USER, "",
                session.toolCallsUsed(), session.tokensUsed());
    }

    private TurnContext contextFor(LoopSession session, LoopConfig config) {
        return new TurnContext(session.sessionId(), assistantCount(session), config.userMessage());
    }

    private static String withNote(String systemPrompt, String note) {
        if (note == null || note.isEmpty()) {
            return systemPrompt;
        }
        return systemPrompt == null || systemPrompt.isEmpty() ? note : systemPrompt + "\n" + note;
    }

    private static int assistantCount(LoopSession session) {
        int count = 0;
        for (Message message : session.messages()) {
            if (message.role() == Role.ASSISTANT) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ chat

    private record ChatResult(LlmTurn turn, LlmResponse response, LlmException failure, boolean budgetExhausted) {

        private static ChatResult ok(LlmTurn turn) {
            return new ChatResult(turn, toResponse(turn), null, false);
        }

        private static ChatResult failed(LlmException failure) {
            return new ChatResult(null, null, failure, false);
        }

        private static ChatResult overBudget() {
            return new ChatResult(null, null, null, true);
        }

        private static LlmResponse toResponse(LlmTurn turn) {
            List<ContentBlock> content = new ArrayList<>();
            if (!turn.text().isEmpty()) {
                content.add(new TextBlock(turn.text()));
            }
            content.addAll(turn.toolCalls());
            return new LlmResponse(content, turn.stopReason(), turn.usage());
        }
    }

    private ChatResult chat(LoopConfig config, LoopSession session, String systemPrompt) {
        for (int attempt = 0; attempt < MAX_CHAT_ATTEMPTS; attempt++) {
            if (session.tokensUsed() >= config.budget().maxTokens()) {
                return ChatResult.overBudget();
            }
            LlmRequest request = new LlmRequest(session.messages(), registry.modelVisibleTools(),
                    systemPrompt, config.model(), LlmRequest.DEFAULT_MAX_TOKENS, null);
            Collector collector = new Collector();
            try {
                llmClient.stream(request, collector);
            } catch (LlmException failure) {
                return ChatResult.failed(failure);
            }
            session.recordTokens(collector.usage());
            LlmTurn turn = collector.turn();
            if (turn.toolCalls().isEmpty() || !collector.hasUnusableCall()) {
                return ChatResult.ok(turn);
            }
        }
        return ChatResult.failed(new LlmException("模型返回了无法解析的工具调用"));
    }

    // ------------------------------------------------------------- execution

    /**
     * Runs one step's calls, appending each result to the history in request order.
     *
     * <p>The step is a sequence of runs: a maximal run of consecutive {@link Permission#READ} calls is
     * executed on virtual threads, a non-{@code READ} call is gated and then run serially. A
     * non-{@code READ} call therefore <em>ends</em> the current read run, so two reads separated by a
     * write belong to different runs and are not forced to overlap: the model's declared order is what
     * decides, and — crucially — a write that defers cannot let a later read run before the resume.
     * The relative order of the writes is preserved.</p>
     *
     * <p>When the gate defers on a call, everything before it is already in the history and the suffix
     * from that call on is returned for the session to store, so {@link #resume} can pick it up.</p>
     *
     * @param from            the first call to process (earlier calls were answered before a resume)
     * @param appendAssistant whether the assistant {@code tool_use} message still needs recording
     */
    private StepOutcome runStep(
            LoopConfig config,
            LoopSession session,
            TurnContext context,
            List<ToolCallRequest> requests,
            int from,
            boolean appendAssistant) {
        if (appendAssistant) {
            appendAssistantToolUses(session, requests);
        }
        int index = Math.max(0, from);
        while (index < requests.size()) {
            if (permissionOf(requests.get(index)) == Permission.READ) {
                int end = index;
                while (end < requests.size() && permissionOf(requests.get(end)) == Permission.READ) {
                    end++;
                }
                int allowed = Math.min(end, index + Math.max(0, remainingToolCalls(config, session)));
                boolean overflowed = allowed < end;
                runReadOnlyBatch(config, session, context, requests.subList(index, allowed));
                for (int rest = allowed; rest < end; rest++) {
                    appendBudgetExhausted(session, requests.get(rest));
                }
                index = end;
                if (overflowed) {
                    return StepOutcome.ofExhausted();
                }
            } else {
                if (remainingToolCalls(config, session) <= 0) {
                    for (int rest = index; rest < requests.size(); rest++) {
                        appendBudgetExhausted(session, requests.get(rest));
                    }
                    return StepOutcome.ofExhausted();
                }
                Gated gated = runGatedCall(session, context, requests.get(index));
                if (gated.deferred()) {
                    return StepOutcome.ofSuspended(
                            gated.deferReason(), requests.subList(index, requests.size()));
                }
                index++;
            }
        }
        return StepOutcome.ofCompleted();
    }

    private int remainingToolCalls(LoopConfig config, LoopSession session) {
        return config.budget().maxToolCalls() - session.toolCallsUsed();
    }

    private void runReadOnlyBatch(
            LoopConfig config,
            LoopSession session,
            TurnContext context,
            List<ToolCallRequest> batch) {
        if (batch.isEmpty()) {
            return;
        }
        for (ToolCallRequest request : batch) {
            if (session.markToolCallEventEmitted(request.callId())) {
                emit(new ToolCallEvent(request.callId(), request.toolName(), request.arguments()));
            }
        }
        // Submit every call before awaiting any of them, so the whole batch is in flight at once.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ToolCallOutcome>> futures = new ArrayList<>(batch.size());
            for (ToolCallRequest request : batch) {
                futures.add(executor.submit(() -> executeOne(context, session, request)));
            }
            for (int slot = 0; slot < batch.size(); slot++) {
                appendResult(session, context, await(futures.get(slot), batch.get(slot)));
                session.recordToolCalls(1);
            }
        }
    }

    private Gated runGatedCall(LoopSession session, TurnContext context, ToolCallRequest request) {
        Permission permission = permissionOf(request);
        // Emitted before the gate on purpose: a deferred call must still be visible to the model as
        // "requested". The session records the callId so a resume re-entering this method does not
        // emit it again - exactly one tool_call per callId, paired with one tool_result.
        emitToolCall(session, request);

        StepDecision preTool = hookChain.beforeTool(
                context, request.callId(), request.toolName(), request.arguments());
        if (preTool.blocked()) {
            appendResult(session, context, new ToolCallOutcome(request, ToolCallOutcome.Status.HOOK_BLOCKED,
                    true, LoopErrorMessages.notRun(preTool.reason())));
            session.recordToolCalls(1);
            return Gated.done();
        }
        JsonNode arguments = hookChain.rewriteArguments(
                context, request.callId(), request.toolName(), request.arguments());
        ToolCallRequest effective = new ToolCallRequest(request.callId(), request.toolName(), arguments);

        if (permission != Permission.READ) {
            PermissionDecision decision = permissionGate.check(context, effective, permission);
            if (decision instanceof PermissionDecision.Deny denied) {
                appendResult(session, context, new ToolCallOutcome(effective,
                        ToolCallOutcome.Status.PERMISSION_DENIED, true, LoopErrorMessages.notRun(denied.reason())));
                session.recordToolCalls(1);
                return Gated.done();
            }
            if (decision instanceof PermissionDecision.Defer deferred) {
                // Nothing is written for the deferred call yet; the tool_call event has been emitted
                // and the matching tool_result is produced when resume answers it.
                return Gated.defer(deferred.question());
            }
        }

        if (circuitBreaker.isOpen(session.sessionId(), request.toolName())) {
            appendResult(session, context, new ToolCallOutcome(effective, ToolCallOutcome.Status.CIRCUIT_OPEN, true,
                    LoopErrorMessages.circuitOpen(request.toolName(),
                            circuitBreaker.consecutiveFailures(session.sessionId(), request.toolName()))));
            session.recordToolCalls(1);
            return Gated.done();
        }

        ToolOutcome outcome;
        try {
            outcome = registry.invoke(request.toolName(), arguments);
        } catch (Throwable failure) {
            // Same contract as executeOne: a tool that throws an Error must be shortened to one line
            // and handed back, never allowed to escape run() - the write/high-risk path is no exception.
            outcome = new ToolFailure(request.toolName(), ToolErrorKind.EXECUTION_ERROR,
                    LoopErrorMessages.notRun("执行出错：" + failure.getClass().getSimpleName()
                            + (failure.getMessage() == null ? "" : "：" + failure.getMessage())).asText());
        }
        recordBreaker(session, outcome);
        ToolCallOutcome result = new ToolCallOutcome(effective,
                outcome.isError() ? ToolCallOutcome.Status.FAILURE : ToolCallOutcome.Status.SUCCESS,
                outcome.isError(), LoopErrorMessages.forOutcome(outcome));
        appendResult(session, context, result);
        session.recordToolCalls(1);
        return Gated.done();
    }

    private ToolCallOutcome executeOne(TurnContext context, LoopSession session, ToolCallRequest request) {
        try {
            return executeChecked(context, session, request);
        } catch (Throwable failure) {
            // Throwable, not just RuntimeException: a tool (or a hook) that throws an Error must be
            // shortened to one line and handed back, never allowed to escape the loop.
            return new ToolCallOutcome(request, ToolCallOutcome.Status.FAILURE, true,
                    LoopErrorMessages.notRun("执行出错：" + failure.getClass().getSimpleName()
                            + (failure.getMessage() == null ? "" : "：" + failure.getMessage())));
        }
    }

    private ToolCallOutcome executeChecked(TurnContext context, LoopSession session, ToolCallRequest request) {
        ToolDefinition definition = registry.find(request.toolName()).orElse(null);
        if (definition == null) {
            return new ToolCallOutcome(request, ToolCallOutcome.Status.FAILURE, true,
                    LoopErrorMessages.notRun("工具不存在：" + request.toolName()));
        }
        StepDecision preTool = hookChain.beforeTool(
                context, request.callId(), request.toolName(), request.arguments());
        if (preTool.blocked()) {
            return new ToolCallOutcome(request, ToolCallOutcome.Status.HOOK_BLOCKED, true,
                    LoopErrorMessages.notRun(preTool.reason()));
        }
        JsonNode arguments = hookChain.rewriteArguments(
                context, request.callId(), request.toolName(), request.arguments());
        ToolCallRequest effective = new ToolCallRequest(request.callId(), request.toolName(), arguments);
        // The read-only path must consume the breaker too: a READ tool never passes through
        // runGatedCall, so without this check its failures would never trip - breaking the promise
        // that three consecutive failures stop the tool for the session regardless of permission.
        if (circuitBreaker.isOpen(session.sessionId(), request.toolName())) {
            return new ToolCallOutcome(effective, ToolCallOutcome.Status.CIRCUIT_OPEN, true,
                    LoopErrorMessages.circuitOpen(request.toolName(),
                            circuitBreaker.consecutiveFailures(session.sessionId(), request.toolName())));
        }
        ToolOutcome outcome = registry.invoke(request.toolName(), arguments);
        recordBreaker(session, outcome);
        // Post-tool observation happens once, in appendResult, for every execution path.
        return new ToolCallOutcome(effective,
                outcome.isError() ? ToolCallOutcome.Status.FAILURE : ToolCallOutcome.Status.SUCCESS,
                outcome.isError(), LoopErrorMessages.forOutcome(outcome));
    }

    private ToolCallOutcome await(Future<ToolCallOutcome> future, ToolCallRequest request) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ToolCallOutcome(request, ToolCallOutcome.Status.FAILURE, true,
                    LoopErrorMessages.notRun("只读工具等待被中断"));
        } catch (ExecutionException failure) {
            // executeOne already turns every throwable into an outcome, so this is a last-resort
            // guard: a failure here still becomes a single-line result, never an exception out of run().
            Throwable cause = failure.getCause();
            String detail = cause == null ? "未知错误" : cause.getClass().getSimpleName();
            return new ToolCallOutcome(request, ToolCallOutcome.Status.FAILURE, true,
                    LoopErrorMessages.notRun("执行出错：" + detail));
        }
    }

    private void recordBreaker(LoopSession session, ToolOutcome outcome) {
        if (outcome instanceof ToolFailure failure) {
            if (failure.errorKind() != ToolErrorKind.UNKNOWN_TOOL) {
                circuitBreaker.recordFailure(session.sessionId(), failure.toolName());
            }
        } else {
            circuitBreaker.recordSuccess(session.sessionId(), outcome.toolName());
        }
    }

    private Permission permissionOf(ToolCallRequest request) {
        return registry.find(request.toolName()).map(ToolDefinition::permission).orElse(Permission.READ);
    }

    // -------------------------------------------------------- history & ending

    private void appendAssistantToolUses(LoopSession session, List<ToolCallRequest> requests) {
        List<ContentBlock> content = new ArrayList<>(requests.size());
        for (ToolCallRequest request : requests) {
            content.add(new ToolUseBlock(request.callId(), request.toolName(), request.arguments()));
        }
        session.append(new Message(Role.ASSISTANT, content));
    }

    private void appendResult(LoopSession session, TurnContext context, ToolCallOutcome outcome) {
        session.append(new Message(Role.TOOL, List.of(
                new ToolResultBlock(outcome.request().callId(), outcome.content(), outcome.isError()))));
        emit(new ToolResultEvent(outcome.request().callId(), outcome.request().toolName(),
                outcome.isError(), outcome.content()));
        hookChain.afterTool(context, outcome.request().callId(), outcome.request().toolName(),
                outcome.isError(), outcome.content());
    }

    private void appendBudgetExhausted(LoopSession session, ToolCallRequest request) {
        emit(new ToolCallEvent(request.callId(), request.toolName(), request.arguments()));
        JsonNode content = LoopErrorMessages.notRun("本次任务的工具调用次数已达上限，该调用未执行。");
        session.append(new Message(Role.TOOL, List.of(
                new ToolResultBlock(request.callId(), content, true))));
        emit(new ToolResultEvent(request.callId(), request.toolName(), true, content));
    }

    private void appendBlockedResults(LoopSession session, List<ToolCallRequest> requests, String reason) {
        List<ContentBlock> content = new ArrayList<>(requests.size());
        for (ToolCallRequest request : requests) {
            JsonNode failed = LoopErrorMessages.notRun(reason);
            content.add(new ToolResultBlock(request.callId(), failed, true));
            // Symmetric with appendBudgetExhausted: a blocked call still appears in the event
            // stream, call then result, so every callId pairs exactly once.
            emitToolCall(session, request);
            emit(new ToolResultEvent(request.callId(), request.toolName(), true, failed));
        }
        session.append(new Message(Role.TOOL, content));
    }

    private LoopResult exhaust(LoopConfig config, LoopSession session, LoopStopReason hinted) {
        List<Message> messages = new ArrayList<>(session.messages());
        messages.add(new Message(Role.USER, List.of(new TextBlock(WRAP_UP_NOTE))));
        TurnContext context = contextFor(session, config);
        StepDecision preModel = hookChain.beforeModel(context);
        if (preModel.blocked()) {
            return blocked(session, preModel.reason());
        }
        Collector collector = new Collector();
        try {
            llmClient.stream(new LlmRequest(messages, List.of(),
                    withNote(config.systemPrompt(), preModel.noteOrEmpty()), config.model(),
                    LlmRequest.DEFAULT_MAX_TOKENS, null), collector);
        } catch (LlmException ignored) {
            // The wrap-up call is best effort: keep whatever the model already produced.
        }
        session.recordTokens(collector.usage());
        String text = collector.turn().text();
        if (!text.isEmpty()) {
            session.append(new Message(Role.ASSISTANT, List.of(new TextBlock(text))));
        }
        return finish(session, hinted, text);
    }

    private LoopResult answered(LoopSession session, String text) {
        return finish(session, LoopStopReason.ANSWERED, text);
    }

    private LoopResult blocked(LoopSession session, String reason) {
        return finish(session, LoopStopReason.BLOCKED, reason);
    }

    private LoopResult fail(LoopSession session, LlmException failure) {
        session.markStatus(LoopSession.Status.DONE);
        return new LoopResult(session.sessionId(), LoopStopReason.ERROR,
                failure.getMessage() == null ? "" : failure.getMessage(),
                session.toolCallsUsed(), session.tokensUsed());
    }

    private LoopResult finish(LoopSession session, LoopStopReason reason, String text) {
        session.markStatus(LoopSession.Status.DONE);
        emit(new AnswerEvent(text));
        return new LoopResult(session.sessionId(), reason, text,
                session.toolCallsUsed(), session.tokensUsed());
    }

    private LoopSession requireSameSession(LoopConfig config, LoopSession session) {
        if (!session.sessionId().equals(config.sessionId())) {
            throw new IllegalArgumentException("config.sessionId does not match the session");
        }
        return session;
    }

    /** Emits the {@code tool_call} event for a call exactly once per session. */
    private void emitToolCall(LoopSession session, ToolCallRequest request) {
        if (session.markToolCallEventEmitted(request.callId())) {
            emit(new ToolCallEvent(request.callId(), request.toolName(), request.arguments()));
        }
    }

    private void emitPlan(List<ToolCallRequest> requests) {
        List<String> steps = requests.stream().map(ToolCallRequest::toolName).toList();
        emit(new PlanEvent("按需调用工具：" + String.join("、", steps), steps));
    }

    private void emit(AgentEvent event) {
        try {
            eventSink.accept(event);
        } catch (RuntimeException ignored) {
            // A broken listener must never break the turn.
        }
    }

    // ------------------------------------------------------------- internals

    private record Gated(boolean deferred, String deferReason) {

        private static Gated done() {
            return new Gated(false, null);
        }

        private static Gated defer(String reason) {
            return new Gated(true, reason);
        }
    }

    private record StepOutcome(
            boolean suspended, String deferReason, List<ToolCallRequest> remaining, boolean exhausted) {

        private static StepOutcome ofCompleted() {
            return new StepOutcome(false, null, List.of(), false);
        }

        private static StepOutcome ofExhausted() {
            return new StepOutcome(false, null, List.of(), true);
        }

        private static StepOutcome ofSuspended(String reason, List<ToolCallRequest> remaining) {
            return new StepOutcome(true, reason, List.copyOf(remaining), false);
        }

        private ToolCallRequest pending() {
            return remaining.get(0);
        }
    }

    /** Reassembles a stream into one turn, keeping only the tool calls that fully arrived. */
    private static final class Collector implements Consumer<LlmStreamEvent> {

        private final StringBuilder text = new StringBuilder();
        private final AuthenticToolCalls toolCalls = new AuthenticToolCalls();
        private StopReason stopReason;
        private TokenUsage usage;

        @Override
        public void accept(LlmStreamEvent event) {
            switch (event) {
                case TextDelta delta -> text.append(delta.text());
                case ToolUseStart start -> toolCalls.onStart(start);
                case ToolUseArgumentsDelta delta -> toolCalls.onArguments(delta);
                case ToolUseEnd end -> toolCalls.onEnd(end);
                case MessageDelta delta -> {
                    if (delta.stopReason() != null) {
                        stopReason = delta.stopReason();
                    }
                    if (delta.usage() != null) {
                        usage = delta.usage();
                    }
                }
            }
        }

        private LlmTurn turn() {
            boolean hasText = !text.isEmpty();
            StopReason reason = stopReason != null ? stopReason : toolCalls.inferredStopReason(hasText);
            return new LlmTurn(text.toString(), toolCalls.calls(), reason, AuthenticToolCalls.orZero(usage));
        }

        private TokenUsage usage() {
            return AuthenticToolCalls.orZero(usage);
        }

        private boolean hasUnusableCall() {
            return toolCalls.hasUnusableCall();
        }
    }
}
