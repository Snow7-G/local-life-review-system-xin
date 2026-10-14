package dev.lifeagent.event;

import java.util.Objects;

/**
 * A fragment of the model's reasoning/text output, streamed as it arrives.
 *
 * @param text the delta, not the accumulated text
 */
public record ThinkingEvent(String text) implements AgentEvent {

    public ThinkingEvent {
        Objects.requireNonNull(text, "text");
    }
}
