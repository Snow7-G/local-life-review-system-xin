package dev.lifeagent.hook;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Runs after a tool call finished. Observational only: the return value is deliberately absent so a
 * hook cannot rewrite what the model sees.
 */
@FunctionalInterface
public interface PostToolHook extends Hook {

    /**
     * @param context  the current step
     * @param callId   the tool-use id
     * @param toolName the tool that ran
     * @param isError  whether the result is an error result
     * @param content  the result that goes back to the model
     */
    void afterTool(
            TurnContext context, String callId, String toolName, boolean isError, JsonNode content);

    @Override
    default String name() {
        return getClass().getSimpleName();
    }
}
