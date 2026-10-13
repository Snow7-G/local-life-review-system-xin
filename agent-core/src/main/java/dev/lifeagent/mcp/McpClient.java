package dev.lifeagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** High-level MCP client backed by an injected, restartable transport. */
public final class McpClient implements AutoCloseable {

    private static final AtomicLong CLIENT_SEQUENCE = new AtomicLong();
    private final McpClientConfig config;
    private final JsonRpcCodec codec;
    private final ObjectMapper mapper;
    private final McpTransport transport;
    private final ScheduledExecutorService lifecycleExecutor;
    private final AtomicBoolean available = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean restartScheduled = new AtomicBoolean();
    private final AtomicInteger restartAttempt = new AtomicInteger();
    private volatile Map<String, JsonNode> toolSchemas = Map.of();

    public McpClient(McpClientConfig config, McpTransport transport) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.codec = new JsonRpcCodec();
        this.mapper = codec.mapper();
        long clientId = CLIENT_SEQUENCE.incrementAndGet();
        this.lifecycleExecutor = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform()
                        .name("mcp-client-" + clientId + "-lifecycle")
                        .daemon(true)
                        .unstarted(runnable));
        if (transport instanceof McpTransportFailureSource failureSource) {
            failureSource.onTransportFailure(ignored -> onTransportUnavailable());
        }
        try {
            transport.start();
            performHandshake();
            available.set(true);
        } catch (RuntimeException e) {
            available.set(false);
            transport.failCurrent("Initial MCP handshake failed", e);
            scheduleRestart();
        }
    }

    public JsonNode callTool(String name, Map<String, Object> args, Duration timeout) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(timeout, "timeout");
        if (!isAvailable()) {
            throw new McpUnavailableException("MCP client is unavailable");
        }
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", mapper.valueToTree(args));
        try {
            return transport.request("tools/call", params, timeout);
        } catch (McpTimeoutException e) {
            if (!transport.isRunning()) {
                onTransportUnavailable();
            }
            throw e;
        } catch (McpUnavailableException e) {
            onTransportUnavailable();
            throw e;
        }
    }

    public boolean isAvailable() {
        if (closed.get() || !available.get()) {
            return false;
        }
        if (!transport.isRunning()) {
            onTransportUnavailable();
            return false;
        }
        return true;
    }

    public Optional<JsonNode> toolSchema(String name) {
        Objects.requireNonNull(name, "name");
        JsonNode schema = toolSchemas.get(name);
        return schema == null ? Optional.empty() : Optional.of(schema.deepCopy());
    }

    private void performHandshake() {
        ObjectNode initialize = mapper.createObjectNode();
        initialize.put("protocolVersion", config.protocolVersion());
        initialize.set("capabilities", mapper.createObjectNode());
        ObjectNode clientInfo = initialize.putObject("clientInfo");
        clientInfo.put("name", config.clientName());
        clientInfo.put("version", config.clientVersion());

        JsonNode initializeResult = transport.request(
                "initialize", initialize, config.handshakeTimeout());
        if (!initializeResult.isObject()) {
            throw new McpProtocolException("initialize result must be an object");
        }
        transport.notify("notifications/initialized", mapper.createObjectNode());

        JsonNode toolsResult = transport.request(
                "tools/list", mapper.createObjectNode(), config.handshakeTimeout());
        JsonNode tools = toolsResult.get("tools");
        if (tools == null || !tools.isArray()) {
            throw new McpProtocolException("tools/list result must contain a tools array");
        }
        Map<String, JsonNode> schemas = new HashMap<>();
        for (JsonNode tool : tools) {
            JsonNode name = tool.get("name");
            JsonNode schema = tool.get("inputSchema");
            if (name == null || !name.isTextual() || name.asText().isBlank()
                    || schema == null || !schema.isObject()) {
                throw new McpProtocolException("tools/list contains an invalid tool definition");
            }
            if (schemas.put(name.asText(), schema.deepCopy()) != null) {
                throw new McpProtocolException("tools/list contains duplicate tool: " + name.asText());
            }
        }
        toolSchemas = Collections.unmodifiableMap(schemas);
    }

    private void onTransportUnavailable() {
        available.set(false);
        toolSchemas = Map.of();
        scheduleRestart();
    }

    private void scheduleRestart() {
        if (closed.get() || !restartScheduled.compareAndSet(false, true)) {
            return;
        }
        int attempt = restartAttempt.getAndIncrement();
        int index = Math.min(attempt, config.restartBackoff().size() - 1);
        long delayNanos = toNanosSaturated(config.restartBackoff().get(index));
        try {
            lifecycleExecutor.schedule(this::restart, delayNanos, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            restartScheduled.set(false);
            if (!closed.get()) {
                throw e;
            }
        }
    }

    private void restart() {
        restartScheduled.set(false);
        if (closed.get()) {
            return;
        }
        try {
            transport.start();
            performHandshake();
            restartAttempt.set(0);
            available.set(true);
        } catch (RuntimeException e) {
            available.set(false);
            transport.failCurrent("MCP restart handshake failed", e);
            scheduleRestart();
        }
    }

    private static long toNanosSaturated(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    int pendingRequestCount() {
        return transport.pendingCount();
    }

    String transportThreadNamePrefix() {
        return transport.threadNamePrefix();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        available.set(false);
        toolSchemas = Map.of();
        lifecycleExecutor.shutdownNow();
        transport.close();
        try {
            lifecycleExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
