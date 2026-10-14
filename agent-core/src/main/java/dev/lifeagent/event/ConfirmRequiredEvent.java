package dev.lifeagent.event;

import java.util.Objects;

/**
 * The loop paused because a tool needs the user's confirmation before it may run.
 *
 * <p>W2-3 only emits this when a {@link dev.lifeagent.loop.PermissionGate} defers a
 * {@code WRITE}/{@code HIGH_RISK} call; issuing and verifying a real confirmation token is W5.</p>
 *
 * @param callId   the pending tool-use id the confirmation is about
 * @param toolName the tool waiting for confirmation
 * @param reason   the gate's model/user-facing explanation
 */
public record ConfirmRequiredEvent(String callId, String toolName, String reason)
        implements AgentEvent {

    public ConfirmRequiredEvent {
        requireNonBlank(callId, "callId");
        requireNonBlank(toolName, "toolName");
        Objects.requireNonNull(reason, "reason");
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
