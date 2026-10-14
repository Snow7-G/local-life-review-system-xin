package dev.lifeagent.loop;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * One tool call as the loop sees it: the provider's {@code tool_use} id, the wire name and the raw
 * arguments.
 *
 * @param callId    the tool-use id that ties request, result and event together
 * @param toolName  the registered tool name, or an unknown one the model invented
 * @param arguments the raw arguments; deep-copied on the way in and out
 */
public record ToolCallRequest(String callId, String toolName, JsonNode arguments) {

    public ToolCallRequest {
        requireNonBlank(callId, "callId");
        requireNonBlank(toolName, "toolName");
        arguments = Objects.requireNonNull(arguments, "arguments").deepCopy();
    }

    @Override
    public JsonNode arguments() {
        return arguments.deepCopy();
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
