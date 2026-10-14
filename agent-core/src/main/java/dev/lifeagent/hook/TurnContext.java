package dev.lifeagent.hook;

import java.util.Objects;

/**
 * A small read-only view of the turn handed to every hook.
 *
 * <p>Deliberately minimal: hooks get what they need to make a decision (who, which step, what the
 * user last said) and nothing they could use to mutate the turn.</p>
 *
 * @param sessionId           the session this turn belongs to
 * @param stepIndex           zero-based step counter within the turn
 * @param latestUntrustedText the most recent untrusted text (the user's message), used by the
 *                            injection check; never {@code null}, may be empty
 */
public record TurnContext(String sessionId, int stepIndex, String latestUntrustedText) {

    public TurnContext {
        Objects.requireNonNull(sessionId, "sessionId");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (stepIndex < 0) {
            throw new IllegalArgumentException("stepIndex must be non-negative");
        }
        Objects.requireNonNull(latestUntrustedText, "latestUntrustedText");
    }
}
