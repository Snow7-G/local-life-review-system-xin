package dev.lifeagent.llm;

import java.util.Objects;

/** A raw JSON fragment. It is deliberately not parsed at this stage. */
public record ToolUseArgumentsDelta(String id, String partialJson) implements LlmStreamEvent {

    public ToolUseArgumentsDelta {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(partialJson, "partialJson");
    }
}
