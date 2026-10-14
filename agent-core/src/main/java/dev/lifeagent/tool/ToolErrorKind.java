package dev.lifeagent.tool;

/** Why a tool call failed; part of {@link ToolFailure} and therefore model-visible. */
public enum ToolErrorKind {

    /** The model called a tool that is not registered. */
    UNKNOWN_TOOL,

    /** The arguments did not match the tool's generated schema, so the business method never ran. */
    INVALID_ARGUMENTS,

    /** The arguments were fine, but the tool itself threw while running. */
    EXECUTION_ERROR
}
