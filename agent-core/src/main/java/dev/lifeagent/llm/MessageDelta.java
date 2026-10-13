package dev.lifeagent.llm;

/** Final-message metadata; either field may be absent, but not both. */
public record MessageDelta(StopReason stopReason, TokenUsage usage) implements LlmStreamEvent {

    public MessageDelta {
        if (stopReason == null && usage == null) {
            throw new IllegalArgumentException("stopReason and usage must not both be null");
        }
    }
}
