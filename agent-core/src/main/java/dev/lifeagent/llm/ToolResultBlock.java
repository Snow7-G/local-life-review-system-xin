package dev.lifeagent.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

public record ToolResultBlock(String toolUseId, JsonNode content, boolean isError)
        implements ContentBlock {

    public ToolResultBlock {
        Objects.requireNonNull(toolUseId, "toolUseId");
        if (toolUseId.isBlank()) {
            throw new IllegalArgumentException("toolUseId must not be blank");
        }
        content = Objects.requireNonNull(content, "content").deepCopy();
    }

    @Override
    public JsonNode content() {
        return content.deepCopy();
    }
}
