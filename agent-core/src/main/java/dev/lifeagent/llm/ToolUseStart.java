package dev.lifeagent.llm;

import java.util.Objects;

public record ToolUseStart(String id, String name) implements LlmStreamEvent {

    public ToolUseStart {
        requireNonBlank(id, "id");
        requireNonBlank(name, "name");
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
