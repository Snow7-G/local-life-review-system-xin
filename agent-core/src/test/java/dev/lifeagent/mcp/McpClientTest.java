package dev.lifeagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClientTest {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void handshakeCachesToolsListSchema() {
        try (McpClient client = newClient(builder())) {
            assertTrue(client.isAvailable());
            JsonNode schema = client.toolSchema("echo").orElseThrow();
            assertEquals("object", schema.path("type").asText());
            assertEquals("string", schema.path("properties").path("value").path("type").asText());
            assertTrue(client.toolSchema("missing").isEmpty());
        }
    }

    @Test
    void fiveConcurrentCallsAreMatchedToTheirOwnIds() {
        try (McpClient client = newClient(builder().maxConcurrentRequests(5));
             ExecutorService callers = Executors.newFixedThreadPool(5)) {
            List<CompletableFuture<JsonNode>> calls = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                int value = i;
                calls.add(CompletableFuture.supplyAsync(() -> client.callTool("concurrent", Map.of(
                        "value", value,
                        "delayMillis", (5 - value) * 40L), CALL_TIMEOUT), callers));
            }

            for (int i = 0; i < calls.size(); i++) {
                JsonNode result = calls.get(i).orTimeout(6, TimeUnit.SECONDS).join();
                assertEquals(i, result.path("arguments").path("value").asInt());
            }
        }
    }

    @Test
    void stderrFloodOfFiveMegabytesDoesNotBlockToolCall() {
        AtomicLong loggedCharacters = new AtomicLong();
        McpClientConfig.Builder config = builder()
                .stderrLogger(line -> loggedCharacters.addAndGet(line.length()));
        try (McpClient client = newClient(config)) {
            JsonNode result = client.callTool("stderr_flood", Map.of(), Duration.ofSeconds(15));

            assertTrue(result.path("ok").asBoolean());
            assertTrue(loggedCharacters.get() > 4L * 1024 * 1024,
                    "the dedicated stderr thread must drain and forward the flood");
        }
    }

    @Test
    void timedOutRequestIsRemovedFromPendingMap() {
        try (McpClient client = newClient(builder().consecutiveTimeoutsBeforeRestart(3))) {
            assertThrows(McpTimeoutException.class,
                    () -> client.callTool("no_response", Map.of(), Duration.ofMillis(200)));
            assertEquals(0, client.pendingRequestCount());

            JsonNode recoveryCall = client.callTool("echo", Map.of("value", "still-alive"), CALL_TIMEOUT);
            assertEquals("still-alive", recoveryCall.path("arguments").path("value").asText());
        }
    }

    @Test
    void childDeathMakesClientUnavailableAndCallsFailWithoutHanging() {
        McpClientConfig.Builder config = builder()
                .restartBackoff(List.of(Duration.ofSeconds(5)));
        try (McpClient client = newClient(config)) {
            assertThrows(McpUnavailableException.class,
                    () -> client.callTool("die", Map.of(), Duration.ofSeconds(3)));
            assertFalse(client.isAvailable());
            assertThrows(McpUnavailableException.class,
                    () -> client.callTool("echo", Map.of(), CALL_TIMEOUT));
        }
    }

    @Test
    void utf8ChineseAndEmojiSurviveACharacterSplitAcrossReads() {
        try (McpClient client = newClient(builder())) {
            String text = "上海探店🙂火锅";
            JsonNode result = client.callTool("unicode", Map.of("text", text), CALL_TIMEOUT);
            assertEquals(text, result.path("text").asText());
        }
    }

    @Test
    void closeStopsReaderThreadsAndRejectsFurtherCalls() throws Exception {
        McpClient client = newClient(builder());
        String prefix = client.transportThreadNamePrefix();
        await(() -> countThreads(prefix) == 2, Duration.ofSeconds(2));
        CompletableFuture<JsonNode> pendingCall = CompletableFuture.supplyAsync(
                () -> client.callTool("no_response", Map.of(), Duration.ofSeconds(30)));
        await(() -> client.pendingRequestCount() == 1, Duration.ofSeconds(2));

        client.close();

        CompletionException closedFailure = assertThrows(CompletionException.class,
                () -> pendingCall.orTimeout(2, TimeUnit.SECONDS).join());
        assertTrue(closedFailure.getCause() instanceof McpUnavailableException);
        await(() -> countThreads(prefix) == 0, Duration.ofSeconds(3));
        assertEquals(0, countThreads(prefix));
        assertThrows(McpUnavailableException.class,
                () -> client.callTool("echo", Map.of(), CALL_TIMEOUT));
    }

    @Test
    void consecutiveTimeoutsRestartProcessAndRepeatHandshake() throws Exception {
        McpClientConfig.Builder config = builder()
                .consecutiveTimeoutsBeforeRestart(2)
                .restartBackoff(List.of(Duration.ofMillis(25)));
        try (McpClient client = newClient(config)) {
            long firstPid = client.callTool("echo", Map.of(), CALL_TIMEOUT).path("pid").asLong();
            assertThrows(McpTimeoutException.class,
                    () -> client.callTool("no_response", Map.of(), Duration.ofMillis(150)));
            assertThrows(McpTimeoutException.class,
                    () -> client.callTool("no_response", Map.of(), Duration.ofMillis(150)));

            await(client::isAvailable, Duration.ofSeconds(5));
            assertTrue(client.toolSchema("echo").isPresent(), "restart must repeat tools/list");
            long restartedPid = client.callTool("echo", Map.of(), CALL_TIMEOUT).path("pid").asLong();
            assertNotEquals(firstPid, restartedPid);
        }
    }

    @Test
    void failedHandshakeLeavesClientUnavailable() {
        McpClientConfig.Builder config = builder("bad-tools")
                .restartBackoff(List.of(Duration.ofSeconds(5)));
        try (McpClient client = newClient(config)) {
            assertFalse(client.isAvailable());
            assertThrows(McpUnavailableException.class,
                    () -> client.callTool("echo", Map.of(), CALL_TIMEOUT));
        }
    }

    private static McpClient newClient(McpClientConfig.Builder builder) {
        return new McpClient(builder.build());
    }

    private static McpClientConfig.Builder builder(String... serverArguments) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(FakeMcpServer.class.getName());
        command.addAll(List.of(serverArguments));
        return McpClientConfig.builder(command)
                .handshakeTimeout(Duration.ofSeconds(5))
                .restartBackoff(List.of(Duration.ofMillis(100), Duration.ofMillis(250)));
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
}
