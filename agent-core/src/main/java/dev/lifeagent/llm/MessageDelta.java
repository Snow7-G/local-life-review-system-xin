package dev.lifeagent.llm;

/**
 * Final-message metadata: either field may be absent, but not both - a provider may report only a
 * stop reason, only token usage, or both.
 *
 * <p>A stream may also carry more than one of these events, and a field that was already reported may
 * be repeated: an adapter emits a delta per terminal signal it observes, so consumers must merge
 * deltas (take the last non-null value of each field) instead of assuming a single final event.
 */
public record MessageDelta(StopReason stopReason, TokenUsage usage) implements LlmStreamEvent {

    public MessageDelta {
        if (stopReason == null && usage == null) {
            throw new IllegalArgumentException("stopReason and usage must not both be null");
        }
    }
}
