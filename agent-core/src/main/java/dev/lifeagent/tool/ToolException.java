package dev.lifeagent.tool;

/**
 * Base type for every tool-declaration and tool-registry failure.
 *
 * <p>These are programming/configuration errors raised while building the registry, not
 * model-visible failures: a tool the model can call but that cannot be declared correctly must fail
 * loudly at startup. Model-visible failures (unknown tool, invalid arguments, business exception)
 * are returned as {@link ToolFailure} instead.</p>
 */
public class ToolException extends IllegalArgumentException {

    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
