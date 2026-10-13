package dev.lifeagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpTransportTest {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(3);

    @Test
    void handshakeCachesToolsListSchemaOverRealHttp() throws Exception {
        try (FakeHttpMcpServer server = new FakeHttpMcpServer();
             McpClient client = server.newClient()) {
            assertTrue(client.isAvailable());
            JsonNode schema = client.toolSchema("echo").orElseThrow();
            assertEquals("object", schema.path("type").asText());
            assertEquals("string", schema.path("properties").path("value").path("type").asText());
        }
    }

    @Test
    void sessionAndNegotiatedProtocolHeadersAreSentAfterInitialize() throws Exception {
        try (FakeHttpMcpServer server = new FakeHttpMcpServer();
             McpClient client = server.newClient()) {
            client.callTool("json", Map.of("value", "header-check"), CALL_TIMEOUT);

            assertTrue(server.requests().stream()
                    .filter(request -> !"initialize".equals(request.method()))
                    .allMatch(request -> FakeHttpMcpServer.SESSION_ID.equals(request.sessionId())));
            assertTrue(server.requests().stream()
                    .filter(request -> !"initialize".equals(request.method()))
                    .allMatch(request -> FakeHttpMcpServer.PROTOCOL_VERSION.equals(
                            request.protocolVersion())));
            assertTrue(server.requests().stream().allMatch(request ->
                    "application/json".equals(request.contentType())
                            && "application/json, text/event-stream".equals(request.accept())));
        }
    }

    @Test
    void toolCallAcceptsApplicationJsonResponse() throws Exception {
        try (FakeHttpMcpServer server = new FakeHttpMcpServer();
             McpClient client = server.newClient()) {
            JsonNode result = client.callTool("json", Map.of("value", 42), CALL_TIMEOUT);

            assertEquals(42, result.path("arguments").path("value").asInt());
        }
    }

    @Test
    void toolCallSkipsUnmatchedSseEventAndUsesMatchingId() throws Exception {
        try (FakeHttpMcpServer server = new FakeHttpMcpServer();
             McpClient client = server.newClient()) {
            JsonNode result = client.callTool("sse", Map.of("value", "matched"), CALL_TIMEOUT);

            assertEquals("matched", result.path("arguments").path("value").asText());
            assertTrue(server.sentUnmatchedSseEvent());
        }
    }

    @Test
    void threeConcurrentHttpCallsKeepTheirOwnIds() throws Exception {
        try (FakeHttpMcpServer server = new FakeHttpMcpServer();
             McpClient client = server.newClient(3);
             ExecutorService callers = Executors.newFixedThreadPool(3)) {
            List<CompletableFuture<JsonNode>> calls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                int value = i;
                calls.add(CompletableFuture.supplyAsync(() -> client.callTool(
                        "concurrent", Map.of("value", value), CALL_TIMEOUT), callers));
            }

            for (int i = 0; i < calls.size(); i++) {
                assertEquals(i, calls.get(i).orTimeout(4, TimeUnit.SECONDS).join()
                        .path("arguments").path("value").asInt());
            }
            assertEquals(3, server.maximumConcurrentCalls());
        }
    }

    @Test
    void timedOutHttpRequestIsRemovedFromPendingMap() throws Exception {
        try (FakeHttpMcpServer server = new FakeHttpMcpServer();
             McpClient client = server.newClient()) {
            assertThrows(McpTimeoutException.class, () -> client.callTool(
                    "no_response", Map.of(), Duration.ofMillis(150)));

            assertEquals(0, client.pendingRequestCount());
            assertTrue(client.isAvailable());
        }
    }

    @Test
    void expiredSessionMakesTransportUnavailableAndFurtherCallsFailFast() throws Exception {
        try (FakeHttpMcpServer server = new FakeHttpMcpServer();
             McpClient client = server.newClient()) {
            McpUnavailableException failure = assertThrows(McpUnavailableException.class,
                    () -> client.callTool("expire_session", Map.of(), CALL_TIMEOUT));

            assertFalse(failure.getMessage().contains("404"));
            assertFalse(client.isAvailable());
            assertThrows(McpUnavailableException.class,
                    () -> client.callTool("json", Map.of(), CALL_TIMEOUT));
        }
    }

    @Test
    void closeCancelsPendingCallStopsHttpThreadsAndRejectsFurtherCalls() throws Exception {
        try (FakeHttpMcpServer server = new FakeHttpMcpServer()) {
            McpClient client = server.newClient();
            String prefix = client.transportThreadNamePrefix();
            await(() -> countThreads(prefix) > 0, Duration.ofSeconds(2));
            CompletableFuture<JsonNode> pendingCall = CompletableFuture.supplyAsync(() ->
                    client.callTool("no_response", Map.of(), Duration.ofSeconds(30)));
            await(() -> client.pendingRequestCount() == 1, Duration.ofSeconds(2));

            client.close();

            CompletionException closedFailure = assertThrows(CompletionException.class,
                    () -> pendingCall.orTimeout(2, TimeUnit.SECONDS).join());
            assertTrue(closedFailure.getCause() instanceof McpUnavailableException);
            await(() -> countThreads(prefix) == 0, Duration.ofSeconds(3));
            assertEquals(0, countThreads(prefix));
            assertThrows(McpUnavailableException.class,
                    () -> client.callTool("json", Map.of(), CALL_TIMEOUT));
        }
    }

    @Test
    void utf8ChineseAndEmojiRoundTripOverHttp() throws Exception {
        try (FakeHttpMcpServer server = new FakeHttpMcpServer();
             McpClient client = server.newClient()) {
            String text = "上海探店🙂火锅";
            JsonNode result = client.callTool("unicode", Map.of("text", text), CALL_TIMEOUT);

            assertEquals(text, result.path("text").asText());
        }
    }

    private static long countThreads(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> thread.getName().startsWith(prefix))
                .count();
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met before " + timeout);
    }

    private record RequestRecord(
            String method,
            String sessionId,
            String protocolVersion,
            String contentType,
            String accept) {
    }

    private static final class FakeHttpMcpServer implements AutoCloseable {

        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final String SESSION_ID = "session-local-life-1";
        private static final String PROTOCOL_VERSION = "2025-06-18";

        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final List<RequestRecord> requests = new CopyOnWriteArrayList<>();
        private final CountDownLatch concurrentCalls = new CountDownLatch(3);
        private final java.util.concurrent.atomic.AtomicInteger callsInFlight =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger maximumConcurrentCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean sentUnmatchedSseEvent =
                new java.util.concurrent.atomic.AtomicBoolean();

        private FakeHttpMcpServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        private McpClient newClient() {
            return newClient(16);
        }

        private McpClient newClient(int maxConcurrentRequests) {
            McpClientConfig config = McpClientConfig.builder()
                    .maxConcurrentRequests(maxConcurrentRequests)
                    .handshakeTimeout(Duration.ofSeconds(2))
                    .restartBackoff(List.of(Duration.ofSeconds(5)))
                    .build();
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
            return new McpClient(config, new HttpTransport(endpoint, config));
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                JsonNode request = MAPPER.readTree(exchange.getRequestBody());
                String method = request.path("method").asText();
                requests.add(new RequestRecord(
                        method,
                        exchange.getRequestHeaders().getFirst("Mcp-Session-Id"),
                        exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        exchange.getRequestHeaders().getFirst("Accept")));

                if ("initialize".equals(method)) {
                    ObjectNode result = MAPPER.createObjectNode();
                    result.put("protocolVersion", PROTOCOL_VERSION);
                    result.set("capabilities", MAPPER.createObjectNode());
                    result.set("serverInfo", MAPPER.createObjectNode()
                            .put("name", "fake-http").put("version", "1"));
                    exchange.getResponseHeaders().set("Mcp-Session-Id", SESSION_ID);
                    sendJson(exchange, request.get("id"), result);
                } else if ("notifications/initialized".equals(method)) {
                    exchange.sendResponseHeaders(202, -1);
                } else if ("tools/list".equals(method)) {
                    sendJson(exchange, request.get("id"), toolsList());
                } else if ("tools/call".equals(method)) {
                    handleToolCall(exchange, request);
                } else {
                    exchange.sendResponseHeaders(400, -1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void handleToolCall(HttpExchange exchange, JsonNode request)
                throws IOException, InterruptedException {
            JsonNode id = request.get("id");
            JsonNode params = request.path("params");
            JsonNode arguments = params.path("arguments");
            String name = params.path("name").asText();
            switch (name) {
                case "no_response" -> {
                    Thread.sleep(3_000);
                    sendJson(exchange, id, MAPPER.createObjectNode().put("late", true));
                }
                case "expire_session" -> exchange.sendResponseHeaders(404, -1);
                case "sse" -> sendSse(exchange, id, arguments);
                case "unicode" -> sendJson(exchange, id,
                        MAPPER.createObjectNode().put("text", arguments.path("text").asText()));
                case "concurrent" -> {
                    int current = callsInFlight.incrementAndGet();
                    maximumConcurrentCalls.accumulateAndGet(current, Math::max);
                    concurrentCalls.countDown();
                    try {
                        concurrentCalls.await(2, TimeUnit.SECONDS);
                        sendJson(exchange, id,
                                MAPPER.createObjectNode().set("arguments", arguments));
                    } finally {
                        callsInFlight.decrementAndGet();
                    }
                }
                default -> sendJson(exchange, id,
                        MAPPER.createObjectNode().set("arguments", arguments));
            }
        }

        private void sendSse(HttpExchange exchange, JsonNode id, JsonNode arguments)
                throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            ObjectNode unmatched = rpcResponse(MAPPER.getNodeFactory().numberNode(999_999),
                    MAPPER.createObjectNode().put("ignored", true));
            ObjectNode matching = rpcResponse(id,
                    MAPPER.createObjectNode().set("arguments", arguments));
            String events = "data: " + MAPPER.writeValueAsString(unmatched) + "\n\n"
                    + "data: " + MAPPER.writeValueAsString(matching) + "\n\n";
            sentUnmatchedSseEvent.set(true);
            exchange.getResponseBody().write(events.getBytes(StandardCharsets.UTF_8));
        }

        private static ObjectNode toolsList() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", MAPPER.createObjectNode()
                    .set("value", MAPPER.createObjectNode().put("type", "string")));
            ObjectNode tool = MAPPER.createObjectNode();
            tool.put("name", "echo");
            tool.set("inputSchema", schema);
            ArrayNode tools = MAPPER.createArrayNode().add(tool);
            return MAPPER.createObjectNode().set("tools", tools);
        }

        private static void sendJson(HttpExchange exchange, JsonNode id, JsonNode result)
                throws IOException {
            byte[] body = MAPPER.writeValueAsBytes(rpcResponse(id, result));
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }

        private static ObjectNode rpcResponse(JsonNode id, JsonNode result) {
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            response.set("result", result);
            return response;
        }

        private List<RequestRecord> requests() {
            return List.copyOf(requests);
        }

        private boolean sentUnmatchedSseEvent() {
            return sentUnmatchedSseEvent.get();
        }

        private int maximumConcurrentCalls() {
            return maximumConcurrentCalls.get();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
