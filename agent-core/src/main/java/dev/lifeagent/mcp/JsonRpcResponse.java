package dev.lifeagent.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"jsonrpc", "id", "result", "error"})
public record JsonRpcResponse(String jsonrpc, JsonNode id, JsonNode result, JsonRpcError error)
        implements JsonRpcMessage {

    public JsonRpcResponse {
        if (!"2.0".equals(jsonrpc)) {
            throw new IllegalArgumentException("jsonrpc must be 2.0");
        }
        Objects.requireNonNull(id, "id");
        if (!id.isIntegralNumber() && !id.isTextual() && !id.isNull()) {
            throw new IllegalArgumentException("id must be an integer, string, or null");
        }
        if ((result == null) == (error == null)) {
            throw new IllegalArgumentException("response must contain exactly one of result or error");
        }
    }

    public static JsonRpcResponse success(JsonNode id, JsonNode result) {
        return new JsonRpcResponse("2.0", id, Objects.requireNonNull(result, "result"), null);
    }

    public static JsonRpcResponse failure(JsonNode id, JsonRpcError error) {
        return new JsonRpcResponse("2.0", id, null, Objects.requireNonNull(error, "error"));
    }
}
