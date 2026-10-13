package dev.lifeagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Executable test fixture launched as a real child process by McpClientTest. */
public final class FakeMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object STDOUT_LOCK = new Object();
    private static final CountDownLatch FIVE_CONCURRENT_CALLS = new CountDownLatch(5);

    /**
     * stderr flood scale: the server writes exactly this many bytes to stderr, with one '\n' as the
     * last byte of every {@link #FLOOD_LINE_BYTES} block. McpClientTest derives its lower bound from
     * these same two numbers, so the fixture and the assertion cannot drift apart silently.
     */
    static final int FLOOD_BYTES = 5 * 1024 * 1024;
    /** Length of one flood line, including its trailing '\n'. */
    static final int FLOOD_LINE_BYTES = 1_024;
    /** Server argument carrying the parent environment variable the child must not be able to see. */
    static final String SENTINEL_ARGUMENT_PREFIX = "env-sentinel=";
    /** Server argument carrying the variable the client injects through its configuration. */
    static final String INJECTED_ARGUMENT_PREFIX = "env-injected=";

    private FakeMcpServer() {
    }

    public static void main(String[] args) throws Exception {
        List<String> serverArguments = Arrays.asList(args);
        boolean badTools = serverArguments.contains("bad-tools");
        ExecutorService calls = Executors.newCachedThreadPool();
        boolean initialized = false;
        try (BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonNode message = MAPPER.readTree(line);
                String method = message.path("method").asText();
                if ("initialize".equals(method)) {
                    ObjectNode result = MAPPER.createObjectNode();
                    result.put("protocolVersion", message.path("params").path("protocolVersion").asText());
                    result.set("capabilities", MAPPER.createObjectNode());
                    result.set("serverInfo", MAPPER.createObjectNode().put("name", "fake").put("version", "1"));
                    respond(message.get("id"), result);
                } else if ("notifications/initialized".equals(method)) {
                    initialized = true;
                } else if ("tools/list".equals(method)) {
                    if (!initialized) {
                        respondError(message.get("id"), -32000, "initialized notification missing");
                    } else if (badTools) {
                        respond(message.get("id"), MAPPER.createObjectNode().put("tools", "invalid"));
                    } else {
                        respond(message.get("id"), toolsList());
                    }
                } else if ("tools/call".equals(method)) {
                    JsonNode copy = message.deepCopy();
                    calls.submit(() -> handleToolCall(copy, serverArguments));
                }
            }
        } finally {
            calls.shutdownNow();
            calls.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    private static ObjectNode toolsList() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", MAPPER.createObjectNode()
                .set("value", MAPPER.createObjectNode().put("type", "string")));
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", "echo");
        tool.put("description", "test echo");
        tool.set("inputSchema", schema);
        ArrayNode tools = MAPPER.createArrayNode().add(tool);
        return MAPPER.createObjectNode().set("tools", tools);
    }

    private static void handleToolCall(JsonNode request, List<String> serverArguments) {
        try {
            JsonNode id = request.get("id");
            JsonNode params = request.path("params");
            String name = params.path("name").asText();
            JsonNode arguments = params.path("arguments");
            if ("concurrent".equals(name)) {
                FIVE_CONCURRENT_CALLS.countDown();
                if (!FIVE_CONCURRENT_CALLS.await(3, TimeUnit.SECONDS)) {
                    respondError(id, -32001, "five calls were not in flight together");
                    return;
                }
            }
            switch (name) {
                case "no_response" -> {
                    return;
                }
                case "die" -> Runtime.getRuntime().halt(17);
                case "dump_env" -> respond(id, environmentReport(serverArguments));
                case "stderr_flood" -> {
                    floodStderr(FLOOD_BYTES);
                    respond(id, MAPPER.createObjectNode().put("ok", true));
                }
                case "unicode" -> respondWithSplitUtf8(id, arguments.path("text").asText());
                default -> {
                    long delayMillis = arguments.path("delayMillis").asLong(0);
                    if (delayMillis > 0) {
                        Thread.sleep(delayMillis);
                    }
                    ObjectNode result = MAPPER.createObjectNode();
                    result.set("arguments", arguments);
                    result.put("pid", ProcessHandle.current().pid());
                    respond(id, result);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reports what this child process can actually see in its own environment. The isolation test
     * asserts on this self-evidence from the child side, so it verifies the real absence of inherited
     * variables rather than the mere fact that {@code ProcessBuilder.environment().clear()} was called.
     */
    private static ObjectNode environmentReport(List<String> serverArguments) {
        ObjectNode report = MAPPER.createObjectNode();
        ArrayNode keys = report.putArray("envKeys");
        new TreeSet<>(System.getenv().keySet()).forEach(keys::add);

        String sentinel = argumentValue(serverArguments, SENTINEL_ARGUMENT_PREFIX);
        report.put("sentinelName", sentinel == null ? "" : sentinel);
        report.put("sentinelPresent", sentinel != null && System.getenv().containsKey(sentinel));

        String injected = argumentValue(serverArguments, INJECTED_ARGUMENT_PREFIX);
        String injectedValue = injected == null ? null : System.getenv().get(injected);
        report.put("injectedName", injected == null ? "" : injected);
        report.put("injectedPresent", injectedValue != null);
        report.put("injectedValue", injectedValue == null ? "" : injectedValue);
        return report;
    }

    private static String argumentValue(List<String> serverArguments, String prefix) {
        return serverArguments.stream()
                .filter(argument -> argument.startsWith(prefix))
                .map(argument -> argument.substring(prefix.length()))
                .findFirst()
                .orElse(null);
    }

    private static void floodStderr(int byteCount) throws IOException {
        byte[] line = new byte[FLOOD_LINE_BYTES];
        Arrays.fill(line, (byte) 'x');
        line[line.length - 1] = '\n';
        int written = 0;
        while (written < byteCount) {
            int length = Math.min(line.length, byteCount - written);
            System.err.write(line, 0, length);
            written += length;
        }
        System.err.flush();
    }

    private static void respondWithSplitUtf8(JsonNode id, String text) throws IOException, InterruptedException {
        ObjectNode result = MAPPER.createObjectNode().put("text", text);
        byte[] bytes = response(id, result).getBytes(StandardCharsets.UTF_8);
        byte[] marker = "🙂".getBytes(StandardCharsets.UTF_8);
        int markerIndex = indexOf(bytes, marker);
        int split = markerIndex < 0 ? bytes.length / 2 : markerIndex + 1;
        synchronized (STDOUT_LOCK) {
            System.out.write(bytes, 0, split);
            System.out.flush();
            Thread.sleep(20);
            System.out.write(bytes, split, bytes.length - split);
            System.out.write('\n');
            System.out.flush();
        }
    }

    private static int indexOf(byte[] bytes, byte[] marker) {
        outer:
        for (int i = 0; i <= bytes.length - marker.length; i++) {
            for (int j = 0; j < marker.length; j++) {
                if (bytes[i + j] != marker[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void respond(JsonNode id, JsonNode result) throws IOException {
        writeLine(response(id, result));
    }

    private static String response(JsonNode id, JsonNode result) throws IOException {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return MAPPER.writeValueAsString(response);
    }

    private static void respondError(JsonNode id, int code, String message) throws IOException {
        ObjectNode error = MAPPER.createObjectNode().put("code", code).put("message", message);
        ObjectNode response = MAPPER.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("error", error);
        writeLine(MAPPER.writeValueAsString(response));
    }

    private static void writeLine(String line) {
        synchronized (STDOUT_LOCK) {
            System.out.println(line);
            System.out.flush();
        }
    }
}
