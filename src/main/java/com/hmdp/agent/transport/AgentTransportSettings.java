package com.hmdp.agent.transport;

/**
 * Non-secret defaults for the conversation endpoint.
 *
 * <p>Either field may be {@code null} or blank, meaning "unset"; {@link #modelOr(String)} and
 * {@link #systemPromptOr(String)} then return {@code null}, which the loop treats as "let the
 * adapter decide".</p>
 *
 * @param defaultModel        the model id used when a request names none, or {@code null}
 * @param defaultSystemPrompt the planner's system prompt, or {@code null}
 */
public record AgentTransportSettings(String defaultModel, String defaultSystemPrompt) {

    /**
     * @param requested the request's model, or {@code null}
     * @return the requested model, or the default when none was given
     */
    public String modelOr(String requested) {
        String chosen = requested == null || requested.isBlank() ? defaultModel : requested;
        return chosen == null || chosen.isBlank() ? null : chosen;
    }

    /**
     * @param requested the request's system prompt, or {@code null}
     * @return the requested prompt, or the default when none was given
     */
    public String systemPromptOr(String requested) {
        String chosen = requested == null || requested.isBlank() ? defaultSystemPrompt : requested;
        return chosen == null || chosen.isBlank() ? null : chosen;
    }
}
