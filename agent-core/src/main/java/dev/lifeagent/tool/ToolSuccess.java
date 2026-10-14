package dev.lifeagent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * A tool ran and produced a result.
 *
 * @param toolName the tool that ran
 * @param result   the serialized result; never {@code null}
 */
public record ToolSuccess(String toolName, JsonNode result) implements ToolOutcome {

    public ToolSuccess {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(result, "result");
    }

    /** @return a deep copy, so the caller cannot mutate whatever the tool returned. */
    @Override
    public JsonNode result() {
        return result.deepCopy();
    }
}
