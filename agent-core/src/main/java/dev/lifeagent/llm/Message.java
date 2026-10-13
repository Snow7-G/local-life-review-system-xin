package dev.lifeagent.llm;

import java.util.List;
import java.util.Objects;

public record Message(Role role, List<ContentBlock> content) {

    public Message {
        Objects.requireNonNull(role, "role");
        content = List.copyOf(Objects.requireNonNull(content, "content"));
    }
}
