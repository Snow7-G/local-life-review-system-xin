package dev.lifeagent.hook;

import dev.lifeagent.llm.LlmResponse;

/**
 * Runs after the model answered and before any requested tool is executed. A block here stops the
 * tool calls of that step (the assistant text is still recorded).
 */
@FunctionalInterface
public interface PostModelHook extends Hook {

    /**
     * @param context  the current step
     * @param response the model's answer
     * @return pass, pass-with-note, or block
     */
    HookResult afterModel(TurnContext context, LlmResponse response);

    @Override
    default String name() {
        return getClass().getSimpleName();
    }
}
