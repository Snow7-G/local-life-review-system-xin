package dev.lifeagent.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"jsonrpc", "method", "params"})
public record JsonRpcNotification(String jsonrpc, String method, JsonNode params)
        implements JsonRpcMessage {

    public JsonRpcNotification(String method, JsonNode params) {
        this("2.0", method, params);
    }

    public JsonRpcNotification {
        if (!"2.0".equals(jsonrpc)) {
            throw new IllegalArgumentException("jsonrpc must be 2.0");
        }
        Objects.requireNonNull(method, "method");
        if (params != null && !params.isObject() && !params.isArray()) {
            throw new IllegalArgumentException("params must be an object or array");
        }
    }
}
