package dev.lifeagent.llm;

import java.util.Objects;

public record TextDelta(String text) implements LlmStreamEvent {

    public TextDelta {
        Objects.requireNonNull(text, "text");
    }
}
