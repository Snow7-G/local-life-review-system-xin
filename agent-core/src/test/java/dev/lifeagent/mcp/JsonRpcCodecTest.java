package dev.lifeagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JsonRpcCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonRpcCodec codec = new JsonRpcCodec(mapper);

    @Test
    void encodesRequestsAndNotificationsAsExactlyOnePhysicalLine() {
        JsonNode params = mapper.createObjectNode().put("text", "first\n第二行🙂");

        String request = codec.encode(new JsonRpcRequest(7, "sample/request", params));
        String notification = codec.encode(new JsonRpcNotification("sample/notification", params));

        assertFalse(request.contains("\n"));
        assertFalse(request.contains("\r"));
        assertFalse(notification.contains("\n"));
        JsonRpcRequest decodedRequest = assertInstanceOf(JsonRpcRequest.class, codec.decode(request));
        assertInstanceOf(JsonRpcNotification.class, codec.decode(notification));
        assertEquals(7, decodedRequest.id().longValue());

        JsonRpcRequest stringId = assertInstanceOf(JsonRpcRequest.class, codec.decode(
                "{\"jsonrpc\":\"2.0\",\"id\":\"request-a\",\"method\":\"sample\"}"));
        assertEquals("request-a", stringId.id().textValue());
    }

    @Test
    void decodesSuccessAndErrorResponses() throws Exception {
        JsonRpcResponse success = (JsonRpcResponse) codec.decode(
                "{\"jsonrpc\":\"2.0\",\"id\":8,\"result\":{\"ok\":true}}");
        JsonRpcResponse failure = (JsonRpcResponse) codec.decode(
                "{\"jsonrpc\":\"2.0\",\"id\":9,\"error\":{\"code\":-1,"
                        + "\"message\":\"bad\",\"data\":{\"why\":\"test\"}}}");

        assertEquals(true, success.result().path("ok").asBoolean());
        assertNotNull(failure.error());
        assertEquals(-1, failure.error().code());
        assertEquals("test", failure.error().data().path("why").asText());
    }
}
