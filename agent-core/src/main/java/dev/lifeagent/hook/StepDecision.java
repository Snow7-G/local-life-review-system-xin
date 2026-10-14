package dev.lifeagent.hook;

import java.util.Objects;

/**
 * The combined outcome of running every hook at one point, for one step.
 *
 * <p>Immutable and produced fresh per call, so nothing is shared between concurrent tool calls.
 * The loop reads exactly three things from it: whether to proceed, why not, and what extra text to
 * append to the model's input.</p>
 *
 * @param blocked {@code true} when at least one hook blocked
 * @param reason  the first block reason, or {@code null} when not blocked
 * @param note    concatenated notes from passing hooks, or {@code null} when there are none
 */
public record StepDecision(boolean blocked, String reason, String note) {

    public StepDecision {
        if (!blocked && reason != null) {
            throw new IllegalArgumentException("a non-blocking decision must not carry a reason");
        }
        if (blocked && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("a blocking decision must carry a reason");
        }
    }

    /** @return a decision that lets the step continue. */
    public static StepDecision pass() {
        return new StepDecision(false, null, null);
    }

    /**
     * Folds one hook's result into this decision.
     *
     * <p>The first block wins and is kept, later blocks do not overwrite it; notes accumulate in
     * order and survive even if a later hook blocks.</p>
     *
     * @param result the hook's result; {@code null} is treated as a block so a misbehaving hook
     *               cannot silently pass
     * @return the merged decision
     */
    public StepDecision merge(HookResult result) {
        if (result == null) {
            return merge(HookResult.block("Hook 未返回结果（已按拦截处理）"));
        }
        String mergedNote = joinNote(note, result.hasNote() ? result.note() : null);
        if (blocked) {
            return new StepDecision(true, reason, mergedNote);
        }
        if (result.isBlocked()) {
            return new StepDecision(true, result.reason(), mergedNote);
        }
        return new StepDecision(false, null, mergedNote);
    }

    private static String joinNote(String first, String second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first + "\n" + second;
    }

    /** @return the note, guaranteed non-null (empty when there is none). */
    public String noteOrEmpty() {
        return Objects.requireNonNullElse(note, "");
    }
}
