package dev.lifeagent.loop;

import java.util.Objects;

/**
 * How one turn ended.
 *
 * @param sessionId  the session that ran
 * @param stopReason why it stopped
 * @param finalText  the answer text; blank for {@link LoopStopReason#WAITING_USER}, and for
 *                   {@link LoopStopReason#ERROR} unless the model had already produced text
 * @param toolCalls  how many tool calls the turn issued
 * @param tokensUsed how many tokens the turn accumulated
 */
public record LoopResult(
        String sessionId, LoopStopReason stopReason, String finalText, int toolCalls, int tokensUsed) {

    public LoopResult {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(finalText, "finalText");
    }

    /** @return {@code true} when the turn produced a final answer. */
    public boolean hasAnswer() {
        return stopReason == LoopStopReason.ANSWERED;
    }
}
