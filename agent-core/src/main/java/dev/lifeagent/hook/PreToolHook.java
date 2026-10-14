package dev.lifeagent.hook;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Runs before one tool call executes. Blocking here skips that single call and hands the reason
 * back to the model as the tool's error result, so the model can choose another route.
 *
 * <p>{@link #rewriteArguments} is the only hook point allowed to change data on the way through:
 * it may tighten arguments (for example clamp a radius) before the call runs.</p>
 */
public interface PreToolHook extends Hook {

    /**
     * Decides whether one tool call may run.
     *
     * @param context   the current step
     * @param callId    the tool-use id
     * @param toolName  the tool the model called
     * @param arguments the arguments the model produced
     * @return pass, pass-with-note, or block
     */
    HookResult beforeTool(TurnContext context, String callId, String toolName, JsonNode arguments);

    /**
     * Optionally rewrites the arguments of a tool call that already passed {@link #beforeTool}.
     *
     * @param context   the current step
     * @param callId    the tool-use id
     * @param toolName  the tool the model called
     * @param arguments the arguments the model produced
     * @return the arguments to use; the default is to leave them untouched
     */
    default JsonNode rewriteArguments(
            TurnContext context, String callId, String toolName, JsonNode arguments) {
        return arguments;
    }

    @Override
    default String name() {
        return getClass().getSimpleName();
    }
}
