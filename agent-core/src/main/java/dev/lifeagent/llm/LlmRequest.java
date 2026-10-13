package dev.lifeagent.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable input shared by all provider adapters. */
public record LlmRequest(
        List<Message> messages,
        List<JsonNode> tools,
        String systemPrompt,
        String model,
        int maxTokens,
        Double temperature) {

    public static final int DEFAULT_MAX_TOKENS = 4_096;

    public LlmRequest(List<Message> messages, List<JsonNode> tools, String systemPrompt) {
        this(messages, tools, systemPrompt, null, DEFAULT_MAX_TOKENS, null);
    }

    public LlmRequest(
            List<Message> messages,
            List<JsonNode> tools,
            String systemPrompt,
            String model,
            int maxTokens) {
        this(messages, tools, systemPrompt, model, maxTokens, null);
    }

    public LlmRequest {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        tools = copyJsonNodes(tools);
        if (model != null && model.isBlank()) {
            throw new IllegalArgumentException("model must be null or non-blank");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (temperature != null
                && (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0)) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
    }

    @Override
    public List<JsonNode> tools() {
        return copyJsonNodes(tools);
    }

    private static List<JsonNode> copyJsonNodes(List<JsonNode> source) {
        Objects.requireNonNull(source, "tools");
        List<JsonNode> copy = new ArrayList<>(source.size());
        for (JsonNode node : source) {
            copy.add(Objects.requireNonNull(node, "tools element").deepCopy());
        }
        return List.copyOf(copy);
    }
}
