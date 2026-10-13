package dev.lifeagent.mcp;

public final class McpTimeoutException extends McpException {

    public McpTimeoutException(String message) {
        super(message);
    }

    public McpTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
