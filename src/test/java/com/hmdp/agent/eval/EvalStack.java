package com.hmdp.agent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.agent.SearchOrigin;
import com.hmdp.agent.SpringShopCatalog;
import dev.lifeagent.event.AgentEvent;
import dev.lifeagent.event.AnswerEvent;
import dev.lifeagent.event.ConfirmRequiredEvent;
import dev.lifeagent.event.PlanEvent;
import dev.lifeagent.event.ThinkingEvent;
import dev.lifeagent.event.ToolCallEvent;
import dev.lifeagent.event.ToolResultEvent;
import dev.lifeagent.hook.HookChain;
import dev.lifeagent.loop.AgentLoop;
import dev.lifeagent.loop.InMemorySessionStore;
import dev.lifeagent.loop.LoopBudget;
import dev.lifeagent.loop.LoopConfig;
import dev.lifeagent.loop.LoopResult;
import dev.lifeagent.loop.PermissionGate;
import dev.lifeagent.tool.ShopTools;
import dev.lifeagent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The evaluation rig for one task's canned world.
 *
 * <p>This is the W2-6 wiring: the real {@link AgentLoop}, the real {@link HookChain}, the real
 * {@link ToolRegistry}, the real {@code ShopTools} and the real {@link SpringShopCatalog} sit between
 * the model and the canned business data, so category resolution, radius filtering and budget
 * filtering all run in production code. The only stand-ins are the model (a scripted LLM in segment
 * A) and the three application services. No Spring context, no MySQL, no Redis, no socket.</p>
 *
 * <p>The loop's event sink is fixed at construction - the seam {@code AgentTransportConfig} uses
 * too - so a fresh loop is built for every run through {@link #run}. That is required for
 * {@code pass^k}, which repeats one task. All runs of one rig share one circuit breaker, the same way
 * a process does; it is taken from a holder loop exactly as the W2-6 harness does, because
 * {@code ToolCircuitBreaker} is package-private to {@code dev.lifeagent.loop}.</p>
 */
final class EvalStack {

    /** The search origin the adapter is configured with; the adapter rejects a null one. */
    static final SearchOrigin ORIGIN = new SearchOrigin(120.15, 30.31);

    /** One tool call as the loop observed it: the arguments it was given and the result returned. */
    record ObservedCall(String callId, String toolName, JsonNode arguments, JsonNode result, boolean isError) {
    }

    /**
     * Everything one task run produced.
     *
     * @param result        how the loop ended
     * @param events        the events in emission order
     * @param calls         the tool calls in emission order, with real arguments and results
     * @param durationNanos wall-clock duration of the turn
     */
    record EvalRun(
            LoopResult result,
            List<AgentEvent> events,
            List<ObservedCall> calls,
            List<dev.lifeagent.llm.TokenUsage> usages,
            long durationNanos) {

        /** The event type names, e.g. {@code plan, tool_call, ...}. */
        List<String> eventNames() {
            List<String> names = new ArrayList<>();
            for (AgentEvent event : events) {
                names.add(EvalStack.eventName(event));
            }
            return names;
        }

        List<String> calledToolNames() {
            List<String> names = new ArrayList<>();
            for (ObservedCall call : calls) {
                names.add(call.toolName());
            }
            return names;
        }

        int toolCallCount() {
            return calls.size();
        }

        /** The tool arguments of the first call of that tool, or {@code null}. */
        JsonNode argumentsOf(String toolName) {
            for (ObservedCall call : calls) {
                if (call.toolName().equals(toolName)) {
                    return call.arguments();
                }
            }
            return null;
        }

        String answer() {
            return result.finalText();
        }
    }

    /** The holder loop is never run; this client fails loudly if that ever changes. */
    private static final dev.lifeagent.llm.LlmClient NEVER_CALLED = (request, events) -> {
        throw new AssertionError("breaker-holder loop 不应被运行");
    };

    private final EvalWorld world;
    private final EvalWorld.Services services;
    private final SpringShopCatalog catalog;
    private final ToolRegistry registry;
    private final HookChain hooks;
    private final AgentLoop breakerSource;

    EvalStack(EvalWorld world) {
        this.world = world;
        this.services = new EvalWorld.Services(world);
        this.catalog = new SpringShopCatalog(
                services.shop.service(), services.voucher.service(), services.shopType.service(), ORIGIN);
        this.registry = new ToolRegistry().registerTools(new ShopTools(catalog));
        this.hooks = new HookChain(List.of(), List.of(), List.of(), List.of());
        this.breakerSource = AgentLoop.create(NEVER_CALLED, registry, hooks, PermissionGate.denyAll());
    }

    /** A rig over the world a task names. */
    static EvalStack forWorld(String worldName) {
        return new EvalStack(EvalWorld.named(worldName));
    }

    /**
     * Runs one turn of one task with {@code llm} as the model.
     *
     * @param userMessage the task's user input
     * @param llm         the model for this run
     * @return the turn's events, tool calls, stop reason and duration
     */
    EvalRun run(String userMessage, EvalScriptedLlm llm) {
        List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        AgentLoop loop = new AgentLoop(llm, registry, hooks, PermissionGate.denyAll(),
                new InMemorySessionStore(), breakerSource.breaker(), events::add);
        long started = System.nanoTime();
        LoopResult result = loop.run(new LoopConfig(
                "eval-session", "你是探店规划助手", null, userMessage, LoopBudget.defaults()));
        long nanos = System.nanoTime() - started;
        return new EvalRun(result, List.copyOf(events), pairCalls(events), llm.usages(), nanos);
    }

    /** Pairs each {@code tool_call} event's arguments with the {@code tool_result} of the same id. */
    private static List<ObservedCall> pairCalls(List<AgentEvent> events) {
        Map<String, JsonNode> arguments = new LinkedHashMap<>();
        for (AgentEvent event : events) {
            if (event instanceof ToolCallEvent call) {
                arguments.put(call.callId(), call.arguments());
            }
        }
        List<ObservedCall> paired = new ArrayList<>();
        for (AgentEvent event : events) {
            if (event instanceof ToolResultEvent result) {
                paired.add(new ObservedCall(result.callId(), result.toolName(),
                        arguments.get(result.callId()), result.content(), result.isError()));
            }
        }
        return paired;
    }

    EvalWorld world() {
        return world;
    }

    EvalWorld.Services services() {
        return services;
    }

    SpringShopCatalog catalog() {
        return catalog;
    }

    ToolRegistry registry() {
        return registry;
    }

    /** The event's short contract name, matching the SSE frame names. */
    static String eventName(AgentEvent event) {
        return switch (event) {
            case PlanEvent ignored -> "plan";
            case ToolCallEvent ignored -> "tool_call";
            case ToolResultEvent ignored -> "tool_result";
            case ThinkingEvent ignored -> "thinking";
            case ConfirmRequiredEvent ignored -> "confirm_required";
            case AnswerEvent ignored -> "answer";
        };
    }
}
