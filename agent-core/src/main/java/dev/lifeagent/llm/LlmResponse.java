package dev.lifeagent.llm;

import java.util.List;
import java.util.Objects;

public record LlmResponse(
        List<ContentBlock> content,
        StopReason stopReason,
        TokenUsage usage) {

    public LlmResponse {
        content = List.copyOf(Objects.requireNonNull(content, "content"));
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(usage, "usage");
    }
}
