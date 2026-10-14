package dev.lifeagent.event;

import java.util.Objects;

/**
 * The turn's final answer. Emitted at most once and always last.
 *
 * @param text the answer text shown to the user
 */
public record AnswerEvent(String text) implements AgentEvent {

    public AnswerEvent {
        Objects.requireNonNull(text, "text");
    }
}
