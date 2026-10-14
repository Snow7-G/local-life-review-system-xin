package dev.lifeagent.hook;

import com.fasterxml.jackson.databind.JsonNode;
import dev.lifeagent.llm.LlmResponse;

import java.util.List;
import java.util.Objects;

/**
 * Runs hooks in a fixed order, for every hook point, and turns a thrown exception into a block.
 *
 * <p><strong>Ordering.</strong> Hooks run in the order the four lists were built - registration
 * order. {@link #beforeModel}, {@link #afterModel} and {@link #beforeTool} visit their hooks left to
 * right and fold the results with {@link StepDecision#merge}; the first block is the one the caller
 * sees, so an earlier hook can always veto a later one. Blocks do not short-circuit the loop over
 * hooks: every registered hook is still visited, so no guard silently stops guarding.</p>
 *
 * <p><strong>Fail-closed.</strong> A hook that throws is converted to
 * {@link HookResult#block(String)} inside {@link #guardResult} - the exception never reaches the
 * main loop. The same rule applies to {@link #rewriteArguments} (the original arguments are kept
 * when a hook throws) and to {@link #afterTool} (an observation failure must not rewrite results,
 * so there is simply nothing to change).</p>
 *
 * <p>Each run method takes its arguments and returns its own value; the chain keeps no per-turn
 * state, so concurrent tool calls sharing one chain cannot interfere.</p>
 */
public final class HookChain {

    private final List<PreModelHook> preModelHooks;
    private final List<PostModelHook> postModelHooks;
    private final List<PreToolHook> preToolHooks;
    private final List<PostToolHook> postToolHooks;

    /**
     * @param preModelHooks  run before every model call, in order
     * @param postModelHooks run after every model call, in order
     * @param preToolHooks   run before every tool call, in order
     * @param postToolHooks  run after every tool call, in order
     */
    public HookChain(
            List<? extends PreModelHook> preModelHooks,
            List<? extends PostModelHook> postModelHooks,
            List<? extends PreToolHook> preToolHooks,
            List<? extends PostToolHook> postToolHooks) {
        this.preModelHooks = List.copyOf(Objects.requireNonNull(preModelHooks, "preModelHooks"));
        this.postModelHooks = List.copyOf(Objects.requireNonNull(postModelHooks, "postModelHooks"));
        this.preToolHooks = List.copyOf(Objects.requireNonNull(preToolHooks, "preToolHooks"));
        this.postToolHooks = List.copyOf(Objects.requireNonNull(postToolHooks, "postToolHooks"));
    }

    /** @return a chain with no hooks at all. */
    public static HookChain empty() {
        return new HookChain(List.of(), List.of(), List.of(), List.of());
    }

    /** Runs every pre-model hook in order. */
    public StepDecision beforeModel(TurnContext context) {
        Objects.requireNonNull(context, "context");
        StepDecision decision = StepDecision.pass();
        for (PreModelHook hook : preModelHooks) {
            decision = decision.merge(guardResult(hook.name(), () -> hook.beforeModel(context)));
        }
        return decision;
    }

    /** Runs every post-model hook in order. */
    public StepDecision afterModel(TurnContext context, LlmResponse response) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(response, "response");
        StepDecision decision = StepDecision.pass();
        for (PostModelHook hook : postModelHooks) {
            decision = decision.merge(guardResult(hook.name(), () -> hook.afterModel(context, response)));
        }
        return decision;
    }

    /** Runs every pre-tool hook in order for one call. */
    public StepDecision beforeTool(TurnContext context, String callId, String toolName, JsonNode arguments) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(arguments, "arguments");
        StepDecision decision = StepDecision.pass();
        for (PreToolHook hook : preToolHooks) {
            decision = decision.merge(
                    guardResult(hook.name(), () -> hook.beforeTool(context, callId, toolName, arguments)));
        }
        return decision;
    }

    /**
     * Runs every pre-tool hook's argument rewrite in order, threading the value through.
     *
     * <p>A hook that throws is skipped for rewriting: its failure was already reported as a block
     * by {@link #beforeTool}, and the previous arguments are kept.</p>
     */
    public JsonNode rewriteArguments(TurnContext context, String callId, String toolName, JsonNode arguments) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(toolName, "toolName");
        JsonNode current = Objects.requireNonNull(arguments, "arguments");
        for (PreToolHook hook : preToolHooks) {
            JsonNode candidate = current;
            current = guardRewrite(hook.name(), candidate,
                    () -> hook.rewriteArguments(context, callId, toolName, candidate));
        }
        return current;
    }

    /** Runs every post-tool hook in order; an observation failure changes nothing. */
    public void afterTool(
            TurnContext context, String callId, String toolName, boolean isError, JsonNode content) {
        Objects.requireNonNull(context, "context");
        for (PostToolHook hook : postToolHooks) {
            guardVoid(hook.name(), () -> hook.afterTool(context, callId, toolName, isError, content));
        }
    }

    private static HookResult guardResult(String hookName, ResultSupplier supplier) {
        try {
            HookResult result = supplier.get();
            return result == null
                    ? HookResult.block("Hook 未返回结果（已按拦截处理）：" + hookName)
                    : result;
        } catch (Exception failure) {
            return HookResult.block(failureMessage(hookName, failure));
        }
    }

    private static JsonNode guardRewrite(String hookName, JsonNode fallback, RewriteSupplier supplier) {
        try {
            JsonNode rewritten = supplier.get();
            return rewritten == null ? fallback : rewritten;
        } catch (Exception failure) {
            return fallback;
        }
    }

    private static void guardVoid(String hookName, VoidAction action) {
        try {
            action.run();
        } catch (Exception ignored) {
            // Observation hooks cannot change the outcome, so a failure has nothing to fall back to.
        }
    }

    private static String failureMessage(String hookName, Exception failure) {
        String detail = failure.getMessage();
        return "Hook 故障（已按拦截处理）：" + hookName + (detail == null ? "" : "：" + detail);
    }

    @FunctionalInterface
    private interface ResultSupplier {
        HookResult get();
    }

    @FunctionalInterface
    private interface RewriteSupplier {
        JsonNode get();
    }

    @FunctionalInterface
    private interface VoidAction {
        void run();
    }
}
