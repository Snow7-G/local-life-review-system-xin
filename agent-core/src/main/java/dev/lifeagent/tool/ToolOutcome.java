package dev.lifeagent.tool;

/**
 * What one tool call produced.
 *
 * <p>Sealed so callers must handle both shapes: the W2-3 loop turns a {@link ToolSuccess} into a
 * normal tool result message and a {@link ToolFailure} into an error tool result, letting the model
 * correct itself instead of the whole turn dying.</p>
 */
public sealed interface ToolOutcome permits ToolSuccess, ToolFailure {

    /** Name of the tool that was called. */
    String toolName();

    /** {@code true} for {@link ToolFailure}. */
    default boolean isError() {
        return this instanceof ToolFailure;
    }
}
