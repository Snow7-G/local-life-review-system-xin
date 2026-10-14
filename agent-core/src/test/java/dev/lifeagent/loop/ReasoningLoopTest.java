package dev.lifeagent.loop;

import dev.lifeagent.event.AgentEvent;
import dev.lifeagent.event.AnswerEvent;
import dev.lifeagent.event.ToolCallEvent;
import dev.lifeagent.event.ToolResultEvent;
import dev.lifeagent.hook.HookChain;
import dev.lifeagent.hook.HookResult;
import dev.lifeagent.hook.PostModelHook;
import dev.lifeagent.hook.PreModelHook;
import dev.lifeagent.hook.TurnContext;
import dev.lifeagent.llm.LlmResponse;
import dev.lifeagent.llm.StopReason;
import dev.lifeagent.llm.TextBlock;
import dev.lifeagent.llm.TokenUsage;
import dev.lifeagent.llm.ToolResultBlock;
import dev.lifeagent.llm.ToolUseBlock;
import dev.lifeagent.llm.Message;
import dev.lifeagent.llm.Role;
import dev.lifeagent.loop.LoopHarness.ProbeTools;
import dev.lifeagent.loop.LoopHarness.RecordingSink;
import dev.lifeagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The W2-3R regressions: the circuit breaker must gate read-only tools, blocking tools must still
 * pair their events, a deferred session must suspend and resume without re-running finished calls,
 * both ceilings must wrap up, a tool that throws an {@link Error} must not escape the loop, and the
 * event stream must pair exactly one {@code tool_call} with one {@code tool_result}.
 */
class ReasoningLoopTest {

    private static final PermissionGate ALLOW_ALL =
            (context, call, permission) -> PermissionDecision.allow();

    // ------------------------------------------------------------- circuit breaker

    /** Mandatory 1: the third failure trips the breaker and the fourth call never enters the tool. */
    @Test
    void circuitOpensOnReadToolsAfterThreeConsecutiveFailures() {
        ProbeTools probe = new ProbeTools();
        RecordingSink sink = new RecordingSink();
        ScriptedLlm llm = new ScriptedLlm(List.of(
                ScriptedLlm.wantsTools(LoopHarness.fail("c1")),
                ScriptedLlm.wantsTools(LoopHarness.fail("c2")),
                ScriptedLlm.wantsTools(LoopHarness.fail("c3")),
                ScriptedLlm.wantsTools(LoopHarness.fail("c4")),
                ScriptedLlm.answer("完了")));

        LoopResult result = LoopHarness.loop(llm, LoopHarness.registry(probe), HookChain.empty(),
                ALLOW_ALL, sink).run(LoopHarness.config("s-breaker-read", "开始", LoopBudget.defaults()));

        assertEquals(LoopStopReason.ANSWERED, result.stopReason());
        assertEquals(3, probe.failingCalls.get(),
                "the fourth call must not enter the tool body - the breaker is open");
        ToolResultBlock fourth = toolResult(llm.lastRequest().messages(), "c4");
        assertTrue(fourth.isError(), "a short-circuited call comes back as an error result");
        assertTrue(fourth.content().asText().contains("已熔断"),
                "the model is told the tool is broken: " + fourth.content().asText());
        assertPairedAndSingleAnswer(sink);
    }

    /** Mandatory 1, the other half: one success clears the counter so the tool is entered again. */
    @Test
    void oneSuccessResetsTheConsecutiveFailureCount() {
        ProbeTools probe = new ProbeTools();
        probe.flakyWriteFailuresAlreadyServed = 2; // calls 1 and 2 fail
        probe.flakyWriteFailOn.add(4);             // call 4 fails again, after the success on call 3
        ScriptedLlm llm = new ScriptedLlm(List.of(
                ScriptedLlm.wantsTools(LoopHarness.flakyWrite("c1")),
                ScriptedLlm.wantsTools(LoopHarness.flakyWrite("c2")),
                ScriptedLlm.wantsTools(LoopHarness.flakyWrite("c3")),
                ScriptedLlm.wantsTools(LoopHarness.flakyWrite("c4")),
                ScriptedLlm.wantsTools(LoopHarness.flakyWrite("c5")),
                ScriptedLlm.answer("done")));

        LoopResult result = LoopHarness.loop(llm, LoopHarness.registry(probe), ALLOW_ALL)
                .run(LoopHarness.config("s-breaker-reset", "开始", LoopBudget.defaults()));

        assertEquals(LoopStopReason.ANSWERED, result.stopReason());
        assertEquals(5, probe.flakyWriteCalls.get(),
                "the success on call 3 must clear the count: without the reset the breaker would open "
                        + "on call 4 and call 5 would never be entered");
        assertFalse(toolResult(llm.lastRequest().messages(), "c5").isError(),
                "call 5 was entered and succeeded");
    }

    /** Mandatory 1: the write path keeps the same breaker semantics as before. */
    @Test
    void circuitAlsoTripsOnWriteTools() {
        ProbeTools probe = new ProbeTools();
        ScriptedLlm llm = new ScriptedLlm(List.of(
                ScriptedLlm.wantsTools(LoopHarness.writeFail("w1")),
                ScriptedLlm.wantsTools(LoopHarness.writeFail("w2")),
                ScriptedLlm.wantsTools(LoopHarness.writeFail("w3")),
                ScriptedLlm.wantsTools(LoopHarness.writeFail("w4")),
                ScriptedLlm.answer("done")));

        LoopResult result = LoopHarness.loop(llm, LoopHarness.registry(probe), ALLOW_ALL)
                .run(LoopHarness.config("s-breaker-write", "下单", LoopBudget.defaults()));

        assertEquals(LoopStopReason.ANSWERED, result.stopReason());
        assertEquals(3, probe.writeFailCalls.get(), "the gated write path must also short-circuit");
    }

    // ------------------------------------------------------------- suspend / resume

    /** Mandatory 2: a Defer suspends with the session stored, and resume replays only the tail. */
    @Test
    void deferSuspendsTheSessionAndResumeReplaysOnlyTheUnexecutedTail() {
        ProbeTools probe = new ProbeTools();
        RecordingSink sink = new RecordingSink();
        InMemorySessionStore store = new InMemorySessionStore();
        AtomicInteger gateDecisions = new AtomicInteger();
        PermissionGate deferFirst =
                (context, call, permission) -> gateDecisions.getAndIncrement() == 0
                        ? PermissionDecision.defer("确认下单？")
                        : PermissionDecision.allow();
        ScriptedLlm llm = new ScriptedLlm(List.of(
                ScriptedLlm.wantsTools(LoopHarness.ping("call-p"), LoopHarness.write("call-w1", "one")),
                ScriptedLlm.answer("已下单")));

        AgentLoop loop = LoopHarness.loop(llm, LoopHarness.registry(probe), deferFirst, store, sink);
        LoopResult first = loop.run(LoopHarness.config("s-suspend", "下单", LoopBudget.defaults()));

        assertEquals(LoopStopReason.WAITING_USER, first.stopReason());
        assertEquals(1, probe.pingCalls.get(), "the read before the deferred write did run");
        assertEquals(0, probe.writeCalls.get(), "the deferred write must not run before the reply");

        LoopSession stored = store.load("s-suspend").orElse(null);
        assertNotNull(stored, "the suspended session must be in the SessionStore");
        assertEquals(LoopSession.Status.WAITING_USER, stored.status());
        assertTrue(stored.isWaitingForUser());
        assertEquals("call-w1", stored.pendingPermission().request().callId());

        LoopResult resumed = loop.resume(stored, "同意");
        assertEquals(LoopStopReason.ANSWERED, resumed.stopReason());
        assertEquals(1, probe.pingCalls.get(),
                "resume only replays the unfinished tail: the completed read is not run twice");
        assertEquals(1, probe.writeCalls.get(), "the deferred write runs exactly once after the reply");
        assertPairedAndSingleAnswer(sink);
    }

    // ------------------------------------------------------------- ceilings

    /** Mandatory 3: the tool-call ceiling wraps up with no tools on the last call. */
    @Test
    void maxStepsWrapsUpWithAToollessFinalCall() {
        ProbeTools probe = new ProbeTools();
        RecordingSink sink = new RecordingSink();
        LoopBudget budget = LoopBudget.defaults().withMaxToolCalls(3);
        ScriptedLlm llm = new ScriptedLlm(List.of(
                ScriptedLlm.wantsTools(LoopHarness.ping("c1")),
                ScriptedLlm.wantsTools(LoopHarness.ping("c2")),
                ScriptedLlm.wantsTools(LoopHarness.ping("c3")),
                ScriptedLlm.wantsTools(LoopHarness.ping("c4")),
                ScriptedLlm.answer("收尾答复")));

        LoopResult result = LoopHarness.loop(llm, LoopHarness.registry(probe), HookChain.empty(),
                ALLOW_ALL, sink).run(LoopHarness.config("s-steps", "开始", budget));

        assertEquals(LoopStopReason.MAX_STEPS, result.stopReason());
        assertEquals("收尾答复", result.finalText());
        assertEquals(3, probe.pingCalls.get(), "only three calls fit under the ceiling");
        assertTrue(llm.lastRequest().tools().isEmpty(),
                "the wrap-up call must not advertise any tools");
        assertTrue(mentionsWrapUpNote(llm.lastRequest().messages()),
                "the wrap-up instruction must be appended");
        assertPairedAndSingleAnswer(sink);
    }

    /** Mandatory 3: the token ceiling takes the same wrap-up path. */
    @Test
    void tokenBudgetExhaustionTakesTheSameWrapUpPath() {
        ProbeTools probe = new ProbeTools();
        RecordingSink sink = new RecordingSink();
        LoopBudget budget = LoopBudget.defaults().withMaxTokens(100);
        ScriptedLlm llm = new ScriptedLlm(List.of(
                toolTurnWithTokens("c1", 150),
                ScriptedLlm.answer("token 收尾")));

        LoopResult result = LoopHarness.loop(llm, LoopHarness.registry(probe), HookChain.empty(),
                ALLOW_ALL, sink).run(LoopHarness.config("s-tokens", "开始", budget));

        assertEquals(LoopStopReason.BUDGET_EXHAUSTED, result.stopReason());
        assertEquals("token 收尾", result.finalText());
        assertEquals(1, probe.pingCalls.get());
        assertTrue(llm.lastRequest().tools().isEmpty(), "the wrap-up call carries no tools");
        assertTrue(mentionsWrapUpNote(llm.lastRequest().messages()));
        assertPairedAndSingleAnswer(sink);
    }

    // ------------------------------------------------------------- errors and blocks

    /** Mandatory Q3 fix: a tool that throws an Error becomes a one-line result, not an exception. */
    @Test
    void aToolThrowingAnErrorBecomesAFailedResultNotAnException() {
        ProbeTools probe = new ProbeTools();
        ScriptedLlm llm = new ScriptedLlm(List.of(
                ScriptedLlm.wantsTools(LoopHarness.readError("call-e")),
                ScriptedLlm.answer("换个工具")));

        LoopResult result = LoopHarness.loop(llm, LoopHarness.registry(probe), ALLOW_ALL)
                .run(LoopHarness.config("s-error", "开始", LoopBudget.defaults()));

        assertEquals(LoopStopReason.ANSWERED, result.stopReason(),
                "an Error from a tool must never escape run()");
        assertEquals(1, probe.errorCalls.get());
        ToolResultBlock failed = toolResult(llm.lastRequest().messages(), "call-e");
        assertTrue(failed.isError());
        assertTrue(failed.content().asText().contains("AssertionError"),
                "the failure names the throwable: " + failed.content().asText());
    }

    /** Mandatory Q3 fix: the WRITE path must contain an Error from the tool just like the read path. */
    @Test
    void aWriteToolThrowingAnErrorAlsoBecomesAFailedResultNotAnException() {
        ProbeTools probe = new ProbeTools();
        ScriptedLlm llm = new ScriptedLlm(List.of(
                ScriptedLlm.wantsTools(LoopHarness.writeError("call-we")),
                ScriptedLlm.answer("换个方式")));

        LoopResult result = LoopHarness.loop(llm, LoopHarness.registry(probe), ALLOW_ALL)
                .run(LoopHarness.config("s-werror", "开始", LoopBudget.defaults()));

        assertEquals(LoopStopReason.ANSWERED, result.stopReason(),
                "an Error from a write tool must never escape run() either");
        assertEquals(1, probe.writeErrorCalls.get(), "the write probe ran exactly once");
        ToolResultBlock failed = toolResult(llm.lastRequest().messages(), "call-we");
        assertTrue(failed.isError());
        assertTrue(failed.content().asText().contains("AssertionError"),
                "the failure names the throwable: " + failed.content().asText());
    }

    /** Self-review (iii): a pre-model block means the model is never called. */
    @Test
    void preModelBlockNeverCallsTheModel() {
        ProbeTools probe = new ProbeTools();
        RecordingSink sink = new RecordingSink();
        PreModelHook blocker = context -> HookResult.block("检测到注入，已拦截");
        HookChain hooks = new HookChain(List.of(blocker), List.of(), List.of(), List.of());
        // An empty script: if the model were called, ScriptedLlm would throw and the turn would ERROR.
        ScriptedLlm llm = new ScriptedLlm(List.of());

        LoopResult result = LoopHarness.loop(llm, LoopHarness.registry(probe), hooks, ALLOW_ALL, sink)
                .run(LoopHarness.config("s-block-pre", "开始", LoopBudget.defaults()));

        assertEquals(LoopStopReason.BLOCKED, result.stopReason());
        assertEquals(0, llm.streamCalls(), "a pre-model block must not call the model at all");
        assertPairedAndSingleAnswer(sink);
    }

    /** Q3 fix: post-model block results now emit events too, so the pairing stays one-to-one. */
    @Test
    void postModelBlockStillEmitsPairedEvents() {
        ProbeTools probe = new ProbeTools();
        RecordingSink sink = new RecordingSink();
        PostModelHook blocker =
                (context, response) -> HookResult.block("post-model 拦截");
        HookChain hooks = new HookChain(List.of(), List.of(blocker), List.of(), List.of());
        ScriptedLlm llm = new ScriptedLlm(List.of(
                ScriptedLlm.wantsTools(LoopHarness.ping("c1"), LoopHarness.ping("c2"))));

        LoopResult result = LoopHarness.loop(llm, LoopHarness.registry(probe), hooks, ALLOW_ALL, sink)
                .run(LoopHarness.config("s-block-post", "开始", LoopBudget.defaults()));

        assertEquals(LoopStopReason.BLOCKED, result.stopReason());
        assertEquals(0, probe.pingCalls.get(), "a blocked step must not run its tools");
        assertPairedAndSingleAnswer(sink);
        assertEquals(2, sink.ofType(ToolCallEvent.class).size(),
                "both requested calls still get a tool_call event");
    }

    // ------------------------------------------------------------- event pairing

    /** Mandatory 6: parallel reads and serial writes still pair exactly one call with one result. */
    @Test
    void parallelAndSerialCallsPairTheirEventsAndAnswerComesLast() {
        ProbeTools probe = new ProbeTools();
        RecordingSink sink = new RecordingSink();
        ScriptedLlm llm = new ScriptedLlm(List.of(
                ScriptedLlm.wantsTools(LoopHarness.readA("call-a"), LoopHarness.readB("call-b"),
                        LoopHarness.write("call-w", "one")),
                ScriptedLlm.answer("done")));

        LoopResult result = LoopHarness.loop(llm, LoopHarness.registry(probe), HookChain.empty(),
                ALLOW_ALL, sink).run(LoopHarness.config("s-pairing", "找店", LoopBudget.defaults()));

        assertEquals(LoopStopReason.ANSWERED, result.stopReason());
        assertPairedAndSingleAnswer(sink);
        assertEquals(List.of("call-a", "call-b", "call-w"),
                sink.ofType(ToolCallEvent.class).stream()
                        .map(event -> ((ToolCallEvent) event).callId()).toList());
    }

    // ------------------------------------------------------------- helpers

    private static LlmTurn toolTurnWithTokens(String callId, int tokens) {
        return new LlmTurn("", List.of(new ToolUseBlock(callId, "probe_ping",
                ScriptedLlm.json("{\"value\":\"x\"}"))), StopReason.TOOL_USE, new TokenUsage(0, tokens));
    }

    private static boolean mentionsWrapUpNote(List<Message> messages) {
        return messages.stream()
                .filter(message -> message.role() == Role.USER)
                .flatMap(message -> message.content().stream())
                .anyMatch(block -> block instanceof TextBlock text && text.text().contains("资源上限"));
    }

    private static ToolResultBlock toolResult(List<Message> messages, String callId) {
        for (Message message : messages) {
            if (message.role() != Role.TOOL) {
                continue;
            }
            for (var block : message.content()) {
                if (block instanceof ToolResultBlock result && result.toolUseId().equals(callId)) {
                    return result;
                }
            }
        }
        throw new AssertionError("no tool result for call " + callId);
    }

    /**
     * The event contract, checked per {@code callId}: every call has exactly one {@code tool_call}
     * and one {@code tool_result}, each result follows its own call, and there is at most one
     * {@code answer}, always last.
     */
    private static void assertPairedAndSingleAnswer(RecordingSink sink) {
        List<AgentEvent> events = sink.events();
        Map<String, Integer> calls = new LinkedHashMap<>();
        Map<String, Integer> results = new LinkedHashMap<>();
        for (AgentEvent event : events) {
            if (event instanceof ToolCallEvent call) {
                calls.merge(call.callId(), 1, Integer::sum);
            } else if (event instanceof ToolResultEvent result) {
                results.merge(result.callId(), 1, Integer::sum);
            }
        }
        assertEquals(calls.keySet(), results.keySet(), "every call must be answered exactly once");
        calls.forEach((callId, count) ->
                assertEquals(1, count, "callId " + callId + " must emit exactly one tool_call"));
        results.forEach((callId, count) ->
                assertEquals(1, count, "callId " + callId + " must emit exactly one tool_result"));

        // Each result must come after its own call.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (AgentEvent event : events) {
            if (event instanceof ToolCallEvent call) {
                seen.add(call.callId());
            } else if (event instanceof ToolResultEvent result) {
                assertTrue(seen.contains(result.callId()),
                        "tool_result for " + result.callId() + " came before its tool_call");
            }
        }

        List<AgentEvent> answers = sink.ofType(AnswerEvent.class);
        assertTrue(answers.size() <= 1, "there must be at most one answer event");
        if (!answers.isEmpty()) {
            assertSame(events.get(events.size() - 1), answers.get(0),
                    "the answer event must be last");
        }
        assertFalse(events.isEmpty());
    }
}
