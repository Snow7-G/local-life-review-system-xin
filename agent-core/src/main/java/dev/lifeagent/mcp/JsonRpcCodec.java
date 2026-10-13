package dev.lifeagent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/** Encodes exactly one compact JSON-RPC message per line. */
public final class JsonRpcCodec {

    private final ObjectMapper mapper;

    public JsonRpcCodec() {
        this(new ObjectMapper());
    }

    public JsonRpcCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public String encode(JsonRpcMessage message) {
        try {
            String encoded = mapper.writeValueAsString(Objects.requireNonNull(message, "message"));
            if (encoded.indexOf('\n') >= 0 || encoded.indexOf('\r') >= 0) {
                throw new McpProtocolException("JSON-RPC encoder produced a raw line break");
            }
            return encoded;
        } catch (JsonProcessingException e) {
            throw new McpProtocolException("Could not encode JSON-RPC message", e);
        }
    }

    public JsonRpcMessage decode(String line) {
        if (line == null || line.isBlank()) {
            throw new McpProtocolException("JSON-RPC line must not be blank");
        }
        try {
            JsonNode root = mapper.readTree(line);
            if (!root.isObject() || !"2.0".equals(root.path("jsonrpc").asText())) {
                throw new McpProtocolException("Invalid JSON-RPC 2.0 message");
            }
            if (root.has("method")) {
                if (!root.get("method").isTextual()) {
                    throw new McpProtocolException("JSON-RPC method must be a string");
                }
                String method = root.get("method").textValue();
                JsonNode params = root.get("params");
                if (root.hasNonNull("id")) {
                    JsonNode id = root.get("id");
                    if (!id.isIntegralNumber() && !id.isTextual()) {
                        throw new McpProtocolException("Request id must be an integer or string");
                    }
                    return new JsonRpcRequest("2.0", id, method, params);
                }
                return new JsonRpcNotification(method, params);
            }
            if (!root.has("id")) {
                throw new McpProtocolException("Response is missing id");
            }
            JsonNode id = root.get("id");
            boolean hasResult = root.has("result");
            boolean hasError = root.hasNonNull("error");
            if (hasResult == hasError) {
                throw new McpProtocolException("Response must contain exactly one of result or error");
            }
            if (hasError) {
                JsonNode error = root.get("error");
                if (!error.isObject() || !error.path("code").canConvertToInt()
                        || !error.path("message").isTextual()) {
                    throw new McpProtocolException("Invalid JSON-RPC error object");
                }
                JsonRpcError rpcError = new JsonRpcError(
                        error.get("code").intValue(), error.get("message").asText(), error.get("data"));
                return JsonRpcResponse.failure(id, rpcError);
            }
            return JsonRpcResponse.success(id, root.get("result"));
        } catch (JsonProcessingException e) {
            throw new McpProtocolException("Could not decode JSON-RPC line", e);
        }
    }

    ObjectMapper mapper() {
        return mapper;
    }
}
