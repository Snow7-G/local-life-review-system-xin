package dev.lifeagent.tool;

/**
 * The code that actually runs a tool.
 *
 * <p>Receives the already-validated, already-bound argument record (or whatever single argument the
 * handler was registered for) and returns the tool result, which the registry serializes with
 * Jackson. Handlers are only ever called through {@link ToolRegistry#invoke}, i.e. after the
 * arguments have passed schema validation and binding.</p>
 *
 * <p>May throw: a business failure becomes an {@link ToolErrorKind#EXECUTION_ERROR} outcome rather
 * than an exception the model never sees. {@link Error}s are not caught on purpose - an
 * {@code OutOfMemoryError} is not a tool result.</p>
 */
@FunctionalInterface
public interface ToolHandler {

    /**
     * Runs the tool.
     *
     * @param arguments the bound argument record
     * @return the tool result; serialized with Jackson
     * @throws Exception when the tool fails
     */
    Object execute(Object arguments) throws Exception;
}
