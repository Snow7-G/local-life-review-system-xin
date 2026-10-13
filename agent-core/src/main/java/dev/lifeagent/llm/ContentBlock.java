package dev.lifeagent.llm;

/** A vendor-neutral, immutable piece of message content. */
public sealed interface ContentBlock permits TextBlock, ToolUseBlock, ToolResultBlock {
}
