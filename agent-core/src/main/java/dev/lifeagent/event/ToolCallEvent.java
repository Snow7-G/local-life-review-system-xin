package dev.lifeagent.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * The model asked for a tool to run.
 *
 * @param callId    the tool-use id; pairs this event with exactly one {@link ToolResultEvent}
 * @param toolName  the wire name the model called
 * @param arguments the raw arguments as the model produced them
 */
public record ToolCallEvent(String callId, String toolName, JsonNode arguments) implements AgentEvent {

    public ToolCallEvent {
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
