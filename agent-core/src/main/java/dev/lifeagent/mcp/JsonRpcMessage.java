package dev.lifeagent.mcp;

/** Marker for JSON-RPC 2.0 messages supported by the line codec. */
public sealed interface JsonRpcMessage
        permits JsonRpcRequest, JsonRpcResponse, JsonRpcNotification {

    String jsonrpc();
}
