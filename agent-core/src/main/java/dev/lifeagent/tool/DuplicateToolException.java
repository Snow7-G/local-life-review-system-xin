package dev.lifeagent.tool;

/** Raised when two tools claim the same {@link AgentTool#name()}. */
public final class DuplicateToolException extends ToolException {

    private final String toolName;

    public DuplicateToolException(String toolName) {
        super("Tool name already registered: " + toolName);
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }
}
