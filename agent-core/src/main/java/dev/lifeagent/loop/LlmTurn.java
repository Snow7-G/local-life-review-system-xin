package dev.lifeagent.loop;

import dev.lifeagent.llm.StopReason;
import dev.lifeagent.llm.TokenUsage;
import dev.lifeagent.llm.ToolUseBlock;

import java.util.List;
import java.util.Objects;

/**
 * One complete model answer, assembled from the streaming events of a single call.
 *
 * @param text       the concatenated text output (possibly empty)
 * @param toolCalls  the tool calls the model asked for, in first-seen order
 * @param stopReason the provider's stop reason, or an inferred one when none was reported
 * @param usage      merged token usage; {@link TokenUsage#ZERO} when none was reported
 */
public record LlmTurn(String text, List<ToolUseBlock> toolCalls, StopReason stopReason, TokenUsage usage) {

    public LlmTurn {
        Objects.requireNonNull(text, "text");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(usage, "usage");
    }
}
