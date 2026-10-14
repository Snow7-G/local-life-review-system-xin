package dev.lifeagent.hook;

import java.util.Objects;

/**
 * What a hook decided: let the turn continue ({@link #pass()}), stop it ({@link #block}), or
 * continue with an extra instruction ({@link #passWithNote}).
 *
 * <p>Exactly one of the three states, so callers can branch on the state instead of guessing from
 * a nullable reason. The failure mode is the opposite of fail-open: a hook that throws is turned
 * into a {@code block} by the loop, never into a {@code pass}.</p>
 */
public final class HookResult {

    private enum State {
        PASS,
        BLOCK
    }

    private static final HookResult PASS = new HookResult(State.PASS, null, null);

    private final State state;
    private final String reason;
    private final String note;

    private HookResult(State state, String reason, String note) {
        this.state = state;
        this.reason = reason;
        this.note = note;
    }

    /** Continue the turn unchanged. */
    public static HookResult pass() {
        return PASS;
    }

    /**
     * Continue the turn and append {@code note} to what the model sees for this step.
     *
     * @param note the extra instruction; must be non-blank
     * @return a passing result carrying the note
     */
    public static HookResult passWithNote(String note) {
        requireNonBlank(note, "note");
        return new HookResult(State.PASS, null, note);
    }

    /**
     * Alias of {@link #passWithNote(String)} under the name the task card uses: append a hint to
     * what the model sees for this step while letting the step proceed.
     *
     * @param note the extra instruction; must be non-blank
     * @return a passing result carrying the note
     */
    public static HookResult inject(String note) {
        return passWithNote(note);
    }

    /**
     * Stop the turn, or stop this single tool call, depending on the hook point.
     *
     * @param reason the explanation; for pre-model it becomes the final answer, for pre-tool it
     *               becomes the tool's error result
     * @return a blocking result
     */
    public static HookResult block(String reason) {
        requireNonBlank(reason, "reason");
        return new HookResult(State.BLOCK, reason, null);
    }

    /** @return {@code true} when the turn (or the tool call) must not proceed. */
    public boolean isBlocked() {
        return state == State.BLOCK;
    }

    /** @return {@code true} when a note should be appended to the model's input. */
    public boolean hasNote() {
        return note != null;
    }

    /** @return the block reason, or {@code null} when not blocked. */
    public String reason() {
        return reason;
    }

    /** @return the note, or {@code null} when there is none. */
    public String note() {
        return note;
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
