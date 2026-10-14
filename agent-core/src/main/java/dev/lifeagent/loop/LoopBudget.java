package dev.lifeagent.loop;

/**
 * The two hard ceilings of one turn.
 *
 * @param maxToolCalls how many tool calls one turn may issue in total; the manual's default is 12
 * @param maxTokens    how many tokens one turn may accumulate across all model calls. The placeholder
 *                     default is generous on purpose - W7 replaces it with a cost-driven value
 */
public record LoopBudget(int maxToolCalls, int maxTokens) {

    /** The manual's default: 12 tool calls, 100k tokens. */
    public static final int DEFAULT_MAX_TOOL_CALLS = 12;

    /** @see #maxTokens */
    public static final int DEFAULT_MAX_TOKENS = 100_000;

    public LoopBudget {
        if (maxToolCalls < 1) {
            throw new IllegalArgumentException("maxToolCalls must be positive");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
    }

    /** @return the manual's defaults. */
    public static LoopBudget defaults() {
        return new LoopBudget(DEFAULT_MAX_TOOL_CALLS, DEFAULT_MAX_TOKENS);
    }

    /** @return the same token budget with a different tool-call ceiling. */
    public LoopBudget withMaxToolCalls(int value) {
        return new LoopBudget(value, maxTokens);
    }

    /** @return the same tool-call ceiling with a different token budget. */
    public LoopBudget withMaxTokens(int value) {
        return new LoopBudget(maxToolCalls, value);
    }
}
