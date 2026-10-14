package dev.lifeagent.tool;

/** Raised when a lookup by name finds no registered tool. */
public final class UnknownToolException extends ToolException {

    private final String toolName;

    public UnknownToolException(String toolName) {
        super("Unknown tool: " + toolName);
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }
}
