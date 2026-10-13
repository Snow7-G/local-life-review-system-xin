package dev.lifeagent.llm;

import java.util.Objects;

public record TextBlock(String text) implements ContentBlock {

    public TextBlock {
        Objects.requireNonNull(text, "text");
    }
}
