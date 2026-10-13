package dev.lifeagent.llm;

public record TokenUsage(int inputTokens, int outputTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0);

    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must be non-negative");
        }
    }

    public int totalTokens() {
        return Math.addExact(inputTokens, outputTokens);
    }
}
