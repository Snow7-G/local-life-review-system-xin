package dev.lifeagent.tool;

/**
 * Raised when a tool declaration cannot be turned into a tool.
 *
 * <p>Examples: a blank {@code @AgentTool} name or description, an annotated method that does not
 * take exactly one record argument, a return type of {@code void}, and an argument record that uses
 * a field type JSON Schema cannot express.</p>
 */
public final class ToolDeclarationException extends ToolException {

    public ToolDeclarationException(String message) {
        super(message);
    }

    public ToolDeclarationException(String message, Throwable cause) {
        super(message, cause);
    }
}
