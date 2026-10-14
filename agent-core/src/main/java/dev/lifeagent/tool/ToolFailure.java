package dev.lifeagent.tool;

import java.util.Objects;

/**
 * A tool call failed.
 *
 * @param toolName  the tool that was called (also for {@link ToolErrorKind#UNKNOWN_TOOL}, so the
 *                  loop can tell the model what it asked for)
 * @param errorKind why it failed
 * @param message   model-facing explanation
 */
public record ToolFailure(String toolName, ToolErrorKind errorKind, String message) implements ToolOutcome {

    public ToolFailure {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(errorKind, "errorKind");
        Objects.requireNonNull(message, "message");
    }
}
