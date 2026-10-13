package dev.lifeagent.mcp;

public final class McpUnavailableException extends McpException {

    public McpUnavailableException(String message) {
        super(message);
    }

    public McpUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
