package dev.lifeagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClientTest {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    /** Environment variable injected into the child through {@code config.environment()}. */
    private static final String INJECTED_ENV_NAME = "MCP_CHILD_INJECTED_SENTINEL";
    private static final String INJECTED_ENV_VALUE = "injected-by-config";

    /**
     * Lower bound on the characters a correct stderr drain must forward during the flood.
     *
     * <p>The fixture writes {@code FLOOD_BYTES = 5 * 1024 * 1024 = 5_242_880} bytes and places one
     * {@code '\n'} as the last byte of every {@code FLOOD_LINE_BYTES = 1_024} block, so the stream
     * carries {@code 5_242_880 / 1_024 = 5_120} lines. {@link java.io.BufferedReader#readLine()}
     * consumes the line terminator without returning it, so each line contributes 1_023 characters
     * to the logger and the most a correct client can ever log is
     * {@code 5_120 * 1_023 = 5_237_760} characters - exactly 5_120 short of 5 MiB. Asserting
     * {@code >= 5 MiB} would therefore reject a fully correct implementation, while the previous
     * {@code > 4 MiB} bound accepts a drain that silently drops a fifth of the flood.
     */
    private static final long FLOOD_MAX_LOGGED_CHARS =
            (long) (FakeMcpServer.FLOOD_BYTES / FakeMcpServer.FLOOD_LINE_BYTES)
                    * (FakeMcpServer.FLOOD_LINE_BYTES - 1);

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
    void stderrFloodOfFiveMegabytesDoesNotBlockToolCall() throws Exception {
        AtomicLong loggedCharacters = new AtomicLong();
        TestClientBuilder config = builder()
                .stderrLogger(line -> loggedCharacters.addAndGet(line.length()));
        try (McpClient client = newClient(config)) {
            JsonNode result = client.callTool("stderr_flood", Map.of(), Duration.ofSeconds(15));

            assertTrue(result.path("ok").asBoolean());
            assertEquals(5_237_760L, FLOOD_MAX_LOGGED_CHARS,
                    "5_242_880 bytes / 1_024 bytes per line * 1_023 returned characters per line");
            await(() -> loggedCharacters.get() >= FLOOD_MAX_LOGGED_CHARS, Duration.ofSeconds(30));
            assertTrue(loggedCharacters.get() >= FLOOD_MAX_LOGGED_CHARS,
                    "the dedicated stderr thread must drain and forward the whole flood, expected at least "
                            + FLOOD_MAX_LOGGED_CHARS + " characters, got " + loggedCharacters.get());
        }
    }
    @Test
    void childEnvironmentIsIsolatedFromTheParentByDefault() {
        String sentinel = parentEnvironmentSentinel();
        TestClientBuilder config = builder(
                        FakeMcpServer.SENTINEL_ARGUMENT_PREFIX + sentinel,
                        FakeMcpServer.INJECTED_ARGUMENT_PREFIX + INJECTED_ENV_NAME)
                .environment(Map.of(INJECTED_ENV_NAME, INJECTED_ENV_VALUE));

        try (McpClient client = newClient(config)) {
            JsonNode report = client.callTool("dump_env", Map.of(), CALL_TIMEOUT);

            // The child reports its own System.getenv(), so the evidence below comes from inside the
            // child process: it shows what the MCP server could really have read, not merely that
            // ProcessBuilder.environment().clear() was invoked.
            Set<String> childKeys = envKeys(report);
            assertEquals(sentinel, report.path("sentinelName").asText(),
                    "the fixture must have been told which variable to look for");
            assertFalse(report.path("sentinelPresent").asBoolean(),
                    "the child must not see the parent variable " + sentinel);
            assertEquals(Set.of(INJECTED_ENV_NAME), childKeys,
                    "the child environment must contain exactly the configured entries, but the child saw "
                            + childKeys);
            assertTrue(report.path("injectedPresent").asBoolean(),
                    "configured entries must reach the child environment");
            assertEquals(INJECTED_ENV_VALUE, report.path("injectedValue").asText());
        }
    }

    @Test
    void optingInToParentInheritanceMakesTheParentEnvironmentVisibleToTheChild() {
        // Positive control for the test above: the very same sentinel must appear once inheritance is
        // requested, which proves that its absence there is real isolation rather than an artifact of
        // the child being unable to report its environment at all.
        String sentinel = parentEnvironmentSentinel();
        TestClientBuilder config = builder(
                        FakeMcpServer.SENTINEL_ARGUMENT_PREFIX + sentinel,
                        FakeMcpServer.INJECTED_ARGUMENT_PREFIX + INJECTED_ENV_NAME)
                .environment(Map.of(INJECTED_ENV_NAME, INJECTED_ENV_VALUE))
                .inheritParentEnvironment(true);

        try (McpClient client = newClient(config)) {
            JsonNode report = client.callTool("dump_env", Map.of(), CALL_TIMEOUT);

            assertTrue(report.path("sentinelPresent").asBoolean(),
                    "opt-in inheritance must expose the parent variable " + sentinel);
            assertTrue(envKeys(report).contains(sentinel));
            assertEquals(INJECTED_ENV_VALUE, report.path("injectedValue").asText(),
                    "configured entries must win over the inherited environment");
        }
    }

    @Test
    void stderrLoggerFailureDoesNotStopTheDrain() throws Exception {
        AtomicLong loggedCharacters = new AtomicLong();
        AtomicInteger loggerCalls = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        TestClientBuilder config = builder().stderrLogger(line -> {
            // Count before throwing: the measurement must not depend on the deliberate failure.
            loggedCharacters.addAndGet(line.length());
            if (loggerCalls.incrementAndGet() == 3) {
                failures.incrementAndGet();
                throw new IllegalStateException("stderr sink is broken on purpose");
            }
        });

        try (McpClient client = newClient(config)) {
            JsonNode result = client.callTool("stderr_flood", Map.of(), Duration.ofSeconds(15));

            assertTrue(result.path("ok").asBoolean(),
                    "a throwing stderr sink must not break the request/response path");
            await(() -> loggedCharacters.get() >= FLOOD_MAX_LOGGED_CHARS, Duration.ofSeconds(30));
            assertEquals(1, failures.get(), "the sink must have been given the chance to throw");
            assertTrue(loggedCharacters.get() >= FLOOD_MAX_LOGGED_CHARS,
                    "draining must continue past the failing line (line 3 of "
                            + FakeMcpServer.FLOOD_BYTES / FakeMcpServer.FLOOD_LINE_BYTES
                            + "), expected at least " + FLOOD_MAX_LOGGED_CHARS + " characters, got "
                            + loggedCharacters.get());
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
        TestClientBuilder config = builder()
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
        TestClientBuilder config = builder()
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
        TestClientBuilder config = builder("bad-tools")
                .restartBackoff(List.of(Duration.ofSeconds(5)));
        try (McpClient client = newClient(config)) {
            assertFalse(client.isAvailable());
            assertThrows(McpUnavailableException.class,
                    () -> client.callTool("echo", Map.of(), CALL_TIMEOUT));
        }
    }

    private static McpClient newClient(TestClientBuilder builder) {
        McpClientConfig clientConfig = builder.clientConfig.build();
        return new McpClient(
                clientConfig, new StdioTransport(builder.stdioConfig.build(), clientConfig));
    }

    private static TestClientBuilder builder(String... serverArguments) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(FakeMcpServer.class.getName());
        command.addAll(List.of(serverArguments));
        return new TestClientBuilder(command);
    }

    /**
     * Names an environment variable that exists in this JVM, which is the parent of the MCP child.
     *
     * <p>A test cannot add variables to its own environment, so instead of exporting a sentinel the
     * child is told which existing variable to look for and reports whether it can see it. {@code PATH}
     * is preferred because it is present in essentially every launch environment; the test fails
     * loudly rather than passing vacuously if this JVM has no environment at all.
     */
    private static String parentEnvironmentSentinel() {
        Map<String, String> parentEnvironment = System.getenv();
        assertFalse(parentEnvironment.isEmpty(),
                "cannot prove isolation: this JVM has no environment to leak");
        return parentEnvironment.containsKey("PATH")
                ? "PATH"
                : parentEnvironment.keySet().iterator().next();
    }

    private static Set<String> envKeys(JsonNode environmentReport) {
        Set<String> keys = new TreeSet<>();
        environmentReport.path("envKeys").forEach(key -> keys.add(key.asText()));
        return keys;
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

    /** Keeps the existing tests' setup fluent while assembling the two split configurations. */
    private static final class TestClientBuilder {

        private final McpClientConfig.Builder clientConfig = McpClientConfig.builder()
                .handshakeTimeout(Duration.ofSeconds(5))
                .restartBackoff(List.of(Duration.ofMillis(100), Duration.ofMillis(250)));
        private final StdioTransportConfig.Builder stdioConfig;

        private TestClientBuilder(List<String> command) {
            this.stdioConfig = StdioTransportConfig.builder(command);
        }

        private TestClientBuilder maxConcurrentRequests(int value) {
            clientConfig.maxConcurrentRequests(value);
            return this;
        }

        private TestClientBuilder restartBackoff(List<Duration> value) {
            clientConfig.restartBackoff(value);
            return this;
        }

        private TestClientBuilder consecutiveTimeoutsBeforeRestart(int value) {
            clientConfig.consecutiveTimeoutsBeforeRestart(value);
            return this;
        }

        private TestClientBuilder environment(Map<String, String> value) {
            stdioConfig.environment(value);
            return this;
        }

        private TestClientBuilder inheritParentEnvironment(boolean value) {
            stdioConfig.inheritParentEnvironment(value);
            return this;
        }

        private TestClientBuilder stderrLogger(java.util.function.Consumer<String> value) {
            stdioConfig.stderrLogger(value);
            return this;
        }
    }
}
