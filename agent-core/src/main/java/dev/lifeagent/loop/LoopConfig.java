package dev.lifeagent.loop;

import java.util.Objects;

/**
 * The inputs of one turn.
 *
 * @param sessionId    the session this turn belongs to
 * @param systemPrompt the system prompt, or {@code null}
 * @param model        the provider model id, or {@code null} to let the adapter decide
 * @param userMessage  the user's message for this turn; also the untrusted text hooks inspect
 * @param budget       the turn's ceilings
 */
public record LoopConfig(
        String sessionId, String systemPrompt, String model, String userMessage, LoopBudget budget) {

    public LoopConfig {
        Objects.requireNonNull(sessionId, "sessionId");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(budget, "budget");
    }

    /**
     * @param sessionId    the session id
     * @param systemPrompt the system prompt, or {@code null}
     * @param userMessage  the user's message
     * @return a config with the default budget and no model override
     */
    public static LoopConfig of(String sessionId, String systemPrompt, String userMessage) {
        return new LoopConfig(sessionId, systemPrompt, null, userMessage, LoopBudget.defaults());
    }

    /** @return the same config with a different model. */
    public LoopConfig withModel(String value) {
        return new LoopConfig(sessionId, systemPrompt, value, userMessage, budget);
    }

    /** @return the same config with a different budget. */
    public LoopConfig withBudget(LoopBudget value) {
        return new LoopConfig(sessionId, systemPrompt, model, userMessage, value);
    }
}
