package dev.lifeagent.llm;

import java.util.function.Consumer;

/** One streaming contract for every supported LLM provider. */
public interface LlmClient {

    void stream(LlmRequest request, Consumer<? super LlmStreamEvent> eventConsumer);
}
