package dev.lifeagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public final class McpRemoteException extends McpException {

    private final int code;
    private final JsonNode data;

    public McpRemoteException(JsonRpcError error) {
        super("MCP server returned " + error.code() + ": " + error.message());
        this.code = error.code();
        this.data = error.data();
    }

    public int code() {
        return code;
    }

    public JsonNode data() {
        return data;
    }
}
