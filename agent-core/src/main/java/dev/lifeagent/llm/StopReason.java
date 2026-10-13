package dev.lifeagent.llm;

public enum StopReason {
    END_TURN,
    TOOL_USE,
    MAX_TOKENS,
    STOP_SEQUENCE,
    CONTENT_FILTER,
    UNKNOWN
}
