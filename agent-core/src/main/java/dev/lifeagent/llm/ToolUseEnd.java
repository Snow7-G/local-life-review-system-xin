package dev.lifeagent.llm;

import java.util.Objects;

public record ToolUseEnd(String id) implements LlmStreamEvent {

    public ToolUseEnd {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }
}
