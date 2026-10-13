package dev.lifeagent.llm;

/** Provider-independent incremental output emitted by every LLM adapter. */
public sealed interface LlmStreamEvent permits
        TextDelta, ToolUseStart, ToolUseArgumentsDelta, ToolUseEnd, MessageDelta {
}
