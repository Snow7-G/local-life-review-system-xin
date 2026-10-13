package dev.lifeagent.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"code", "message", "data"})
public record JsonRpcError(int code, String message, JsonNode data) {

    public JsonRpcError {
        Objects.requireNonNull(message, "message");
    }
}
