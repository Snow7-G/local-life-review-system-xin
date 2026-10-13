package dev.lifeagent.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"jsonrpc", "id", "method", "params"})
public record JsonRpcRequest(String jsonrpc, JsonNode id, String method, JsonNode params)
        implements JsonRpcMessage {

    public JsonRpcRequest(long id, String method, JsonNode params) {
        this("2.0", LongNode.valueOf(id), method, params);
    }

    public JsonRpcRequest(String id, String method, JsonNode params) {
        this("2.0", TextNode.valueOf(Objects.requireNonNull(id, "id")), method, params);
    }

    public JsonRpcRequest {
        if (!"2.0".equals(jsonrpc)) {
            throw new IllegalArgumentException("jsonrpc must be 2.0");
        }
        Objects.requireNonNull(id, "id");
        if (!id.isIntegralNumber() && !id.isTextual()) {
            throw new IllegalArgumentException("id must be an integer or string");
        }
        Objects.requireNonNull(method, "method");
        if (params != null && !params.isObject() && !params.isArray()) {
            throw new IllegalArgumentException("params must be an object or array");
        }
    }
}
