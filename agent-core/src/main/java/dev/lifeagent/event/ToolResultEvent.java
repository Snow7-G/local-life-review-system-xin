package dev.lifeagent.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * A tool finished - succeeded, failed, was gated or was short-circuited by a circuit breaker.
 *
 * @param callId   the tool-use id this result answers
 * @param toolName the tool that was called
 * @param isError  {@code true} when the model receives this as an error result
 * @param content  what goes back to the model; text for failures, JSON for successes
 */
public record ToolResultEvent(String callId, String toolName, boolean isError, JsonNode content)
        implements AgentEvent {

    public ToolResultEvent {
        requireNonBlank(callId, "callId");
        requireNonBlank(toolName, "toolName");
        content = Objects.requireNonNull(content, "content").deepCopy();
    }

    @Override
    public JsonNode content() {
        return content.deepCopy();
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
