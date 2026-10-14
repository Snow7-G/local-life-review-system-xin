package dev.lifeagent.loop;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * What the loop decided and produced for one tool call.
 *
 * @param request the call this outcome answers
 * @param status  how the call ended
 * @param isError whether the result is handed back to the model as an error result
 * @param content what the model receives: JSON for a success, a single-line message otherwise
 */
public record ToolCallOutcome(ToolCallRequest request, Status status, boolean isError, JsonNode content) {

    /** How a tool call ended. Only {@link #SUCCESS}/{@link #FAILURE} are real executions. */
    public enum Status {

        /** The tool ran and returned a value. */
        SUCCESS,

        /** The tool ran and failed; the failure goes back to the model as one line of text. */
        FAILURE,

        /** A pre-tool hook blocked this call before it ran. */
        HOOK_BLOCKED,

        /** The permission gate refused this call before it ran. */
        PERMISSION_DENIED,

        /** The circuit breaker for {@code (sessionId, toolName)} was open; the tool was not entered. */
        CIRCUIT_OPEN,

        /** The permission gate asked for the user; the session is suspended and this call is pending. */
        DEFERRED,

        /** The turn's tool-call ceiling was already reached, so this call was not issued. */
        TOOL_BUDGET_EXHAUSTED
    }

    public ToolCallOutcome {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(status, "status");
        content = Objects.requireNonNull(content, "content").deepCopy();
    }

    @Override
    public JsonNode content() {
        return content.deepCopy();
    }

    /** @return {@code true} when the call was actually entered. */
    public boolean wasExecuted() {
        return status == Status.SUCCESS || status == Status.FAILURE;
    }
}
