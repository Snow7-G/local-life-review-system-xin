package dev.lifeagent.loop;

import java.util.Objects;

/**
 * The verdict of a {@link PermissionGate} for one non-{@code READ} tool call.
 *
 * <p>Three states, no fourth: a gate cannot "sort of" allow a call. This is the W2-3 half of the
 * permission layer; the confirmation-token flow that turns a {@link Defer} into an {@link Allow}
 * belongs to W5.</p>
 */
public sealed interface PermissionDecision
        permits PermissionDecision.Allow, PermissionDecision.Deny, PermissionDecision.Defer {

    /** The call may run. */
    record Allow() implements PermissionDecision {
    }

    /**
     * The call must not run.
     *
     * @param reason model-facing explanation, sent back as the call's error result
     */
    record Deny(String reason) implements PermissionDecision {

        public Deny {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }

    /**
     * The loop must ask the user before this call runs; the session is suspended.
     *
     * @param question what to ask the user
     */
    record Defer(String question) implements PermissionDecision {

        public Defer {
            Objects.requireNonNull(question, "question");
            if (question.isBlank()) {
                throw new IllegalArgumentException("question must not be blank");
            }
        }
    }

    /** @return an allowing decision. */
    static PermissionDecision allow() {
        return new Allow();
    }

    /**
     * @param reason the model-facing reason the call was refused
     * @return a denying decision
     */
    static PermissionDecision deny(String reason) {
        return new Deny(reason);
    }

    /**
     * @param question what to ask the user
     * @return a deferring decision
     */
    static PermissionDecision defer(String question) {
        return new Defer(question);
    }
}
