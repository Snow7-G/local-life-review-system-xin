package dev.lifeagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpTransportTest {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(3);
    private static final ObjectMapper TEST_MAPPER = new ObjectMapper();

    /**
     * Shape of the fake server's response to a {@code notifications/*} request.
     *
     * <p>{@code explicitContentLength} reproduces the difference between a 202 that simply
     * carries no body and a 202 that spells out {@code Content-Length: 0}.
     */
    private enum NotifyResponse {
        ACCEPTED_WITHOUT_BODY(202, -1, false),
        ACCEPTED_ZERO_LENGTH(202, -1, true),
        NO_CONTENT(204, -1, false);

        private final int status;
        private final long bodyLength;
        private final boolean explicitContentLength;

        NotifyResponse(int status, long bodyLength, boolean explicitContentLength) {
            this.status = status;
            this.bodyLength = bodyLength;
            this.explicitContentLength = explicitContentLength;
        }
    }

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
    void rehandshakeAfterExpiredSessionSendsNoStaleSessionIdAndRecovers() throws Exception {
        try (FakeHttpMcpServer server = new FakeHttpMcpServer();
             McpClient client = server.newClient(Duration.ofMillis(50))) {
            assertThrows(McpUnavailableException.class,
                    () -> client.callTool("expire_session", Map.of(), CALL_TIMEOUT));
            assertEquals(1, initializeRequests(server).size());

            await(() -> initializeRequests(server).size() >= 2, Duration.ofSeconds(5));
            RequestRecord rehandshake = initializeRequests(server).get(1);
            assertNull(rehandshake.sessionId(),
                    "re-handshake must not replay the expired Mcp-Session-Id");

            assertEquals(2, initializeRequests(server).size(),
                    "the recovered handshake must reach the server exactly once more");
            await(client::isAvailable, Duration.ofSeconds(5));
            assertTrue(client.isAvailable());

            JsonNode result = client.callTool("json", Map.of("value", "after-rehandshake"),
                    CALL_TIMEOUT);
            assertEquals("after-rehandshake", result.path("arguments").path("value").asText());
            assertTrue(server.requests().stream()
                    .filter(request -> !"initialize".equals(request.method()))
                    .allMatch(request -> request.sessionId() == null
                            || FakeHttpMcpServer.SESSION_ID.equals(request.sessionId())));
            assertTrue(server.requests().stream()
                    .filter(request -> "tools/call".equals(request.method()))
                    .anyMatch(request -> FakeHttpMcpServer.SESSION_ID.equals(request.sessionId())),
                    "the recovered call must carry the session id issued by the re-handshake");
        }
    }

    @ParameterizedTest
    @EnumSource(NotifyResponse.class)
    void notifyAcceptsEmptySuccessResponseAndKeepsTransportRunning(NotifyResponse response)
            throws Exception {
        try (FakeHttpMcpServer server = new FakeHttpMcpServer();
             McpClient client = server.newClient()) {
            server.setNotifyResponse(response);
            // the handshake itself posts notifications/initialized; ignore that one
            server.resetNotifyObservation();

            assertDoesNotThrow(() -> server.notifyTransport()
                    .notify("notifications/list_changed",
                            TEST_MAPPER.createObjectNode()));

            assertEquals("notifications/list_changed", server.notifyMethod(),
                    "fixture never observed the notification reach the fake server");
            assertTrue(server.notifyTransport().isRunning());
            assertTrue(client.isAvailable());
            client.callTool("json", Map.of("value", "after-notify"), CALL_TIMEOUT);
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

    private static List<RequestRecord> initializeRequests(FakeHttpMcpServer server) {
        return server.requests().stream()
                .filter(request -> "initialize".equals(request.method()))
                .toList();
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
        private final java.util.concurrent.atomic.AtomicReference<String> notifyMethod =
                new java.util.concurrent.atomic.AtomicReference<>();
        private volatile NotifyResponse notifyResponse = NotifyResponse.ACCEPTED_WITHOUT_BODY;
        private volatile McpTransport notifyTransport;

        private FakeHttpMcpServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        private McpClient newClient() {
            return newClient(16);
        }

        private McpClient newClient(Duration restartBackoff) {
            return newClient(16, restartBackoff);
        }

        private McpClient newClient(int maxConcurrentRequests) {
            return newClient(maxConcurrentRequests, Duration.ofSeconds(5));
        }

        private McpClient newClient(int maxConcurrentRequests, Duration restartBackoff) {
            McpClientConfig config = McpClientConfig.builder()
                    .maxConcurrentRequests(maxConcurrentRequests)
                    .handshakeTimeout(Duration.ofSeconds(2))
                    .restartBackoff(List.of(restartBackoff))
                    .build();
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
            McpTransport transport = new HttpTransport(endpoint, config);
            this.notifyTransport = transport;
            return new McpClient(config, transport);
        }

        private void setNotifyResponse(NotifyResponse notifyResponse) {
            this.notifyResponse = notifyResponse;
        }

        private void resetNotifyObservation() {
            this.notifyMethod.set(null);
        }

        private McpTransport notifyTransport() {
            return notifyTransport;
        }

        private String notifyMethod() {
            return notifyMethod.get();
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
                } else if (method != null && method.startsWith("notifications/")) {
                    notifyMethod.set(method);
                    NotifyResponse response = notifyResponse;
                    if (response.explicitContentLength) {
                        exchange.getResponseHeaders().set("Content-Length", "0");
                    }
                    exchange.sendResponseHeaders(response.status, response.bodyLength);
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
