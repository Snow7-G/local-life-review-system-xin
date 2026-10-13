package dev.lifeagent.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

public record ToolUseBlock(String id, String name, JsonNode arguments) implements ContentBlock {

    public ToolUseBlock {
        requireNonBlank(id, "id");
        requireNonBlank(name, "name");
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
