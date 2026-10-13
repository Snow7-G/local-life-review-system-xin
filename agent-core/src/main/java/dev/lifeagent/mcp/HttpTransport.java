package dev.lifeagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** MCP Streamable HTTP transport supporting JSON and server-sent-event responses. */
public final class HttpTransport implements McpTransport, McpTransportFailureSource {

    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String SSE_CONTENT_TYPE = "text/event-stream";
    private static final AtomicLong TRANSPORT_SEQUENCE = new AtomicLong();

    private final URI endpoint;
    private final McpClientConfig config;
    private final JsonRpcCodec codec = new JsonRpcCodec();
    private final Semaphore inFlight;
    private final ConcurrentHashMap<Long, PendingRequest> pending = new ConcurrentHashMap<>();
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicLong generations = new AtomicLong();
    private final AtomicInteger consecutiveTimeouts = new AtomicInteger();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Consumer<McpUnavailableException>> failureListener =
            new AtomicReference<>(ignored -> { });
    private final Object lifecycleLock = new Object();
    private final String threadNamePrefix;
    private final ExecutorService httpExecutor;
    private final HttpClient httpClient;

    private volatile long activeGeneration;
    private volatile String sessionId;
    private volatile String negotiatedProtocolVersion;

    public HttpTransport(URI endpoint, McpClientConfig config) {
        this.endpoint = validateEndpoint(endpoint);
        this.config = Objects.requireNonNull(config, "config");
        this.inFlight = new Semaphore(config.maxConcurrentRequests(), true);
        this.threadNamePrefix = "mcp-http-" + TRANSPORT_SEQUENCE.incrementAndGet() + "-";
        AtomicLong workerSequence = new AtomicLong();
        this.httpExecutor = Executors.newCachedThreadPool(runnable -> Thread.ofPlatform()
                .name(threadNamePrefix + "worker-" + workerSequence.incrementAndGet())
                .daemon(true)
                .unstarted(runnable));
        this.httpClient = HttpClient.newBuilder()
                .executor(httpExecutor)
                .connectTimeout(config.handshakeTimeout())
                .build();
    }

    private static URI validateEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        String scheme = endpoint.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || endpoint.getHost() == null) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URI");
        }
        return endpoint;
    }

    @Override
    public void start() {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new McpUnavailableException("MCP HTTP transport is closed");
            }
            if (!running.compareAndSet(false, true)) {
                throw new McpUnavailableException("MCP HTTP transport is already running");
            }
            activeGeneration = generations.incrementAndGet();
            sessionId = null;
            negotiatedProtocolVersion = null;
            consecutiveTimeouts.set(0);
        }
    }

    @Override
    public JsonNode request(String method, JsonNode params, Duration timeout) {
        Objects.requireNonNull(method, "method");
        validateTimeout(timeout);
        long deadline = deadlineAfter(timeout);
        acquirePermit(method, deadline);
        long id = 0;
        PendingRequest pendingRequest = null;
        try {
            long generation = liveGeneration();
            id = requestIds.incrementAndGet();
            pendingRequest = new PendingRequest(generation, method);
            pending.put(id, pendingRequest);

            HttpRequest request = requestBuilder(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            codec.encode(new JsonRpcRequest(id, method, params)),
                            StandardCharsets.UTF_8))
                    .build();
            CompletableFuture<HttpResponse<InputStream>> exchange = httpClient.sendAsync(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            pendingRequest.exchange = exchange;
            if (!pending.containsKey(id)) {
                exchange.cancel(true);
            }
            PendingRequest expected = pendingRequest;
            long expectedId = id;
            exchange.whenComplete((response, failure) ->
                    handleExchange(expectedId, method, expected, response, failure));

            long remaining = remainingNanos(deadline);
            if (remaining <= 0) {
                throw new TimeoutException("request deadline elapsed while sending");
            }
            return pendingRequest.result.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            if (pendingRequest != null && pending.remove(id, pendingRequest)) {
                pendingRequest.cancelExchange();
                recordTimeout(pendingRequest.generation);
            }
            throw new McpTimeoutException("MCP request timed out: " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (pendingRequest != null && pending.remove(id, pendingRequest)) {
                pendingRequest.cancelExchange();
            }
            throw new McpUnavailableException("Interrupted while waiting for MCP response", e);
        } catch (ExecutionException e) {
            Throwable cause = unwrap(e);
            if (cause instanceof McpTimeoutException timeoutException) {
                if (pendingRequest != null) {
                    recordTimeout(pendingRequest.generation);
                }
                throw timeoutException;
            }
            if (cause instanceof McpException mcpException) {
                throw mcpException;
            }
            throw new McpUnavailableException("MCP HTTP request failed", cause);
        } catch (RuntimeException e) {
            if (pendingRequest != null && pending.remove(id, pendingRequest)) {
                pendingRequest.cancelExchange();
            }
            if (e instanceof McpException mcpException) {
                throw mcpException;
            }
            McpUnavailableException failure =
                    new McpUnavailableException("Could not send MCP HTTP request", e);
            if (pendingRequest != null) {
                failGeneration(pendingRequest.generation, failure);
            }
            throw failure;
        } finally {
            if (pendingRequest != null) {
                pending.remove(id, pendingRequest);
            }
            inFlight.release();
        }
    }

    private void handleExchange(long id, String method, PendingRequest expected,
                                HttpResponse<InputStream> response, Throwable failure) {
        if (pending.get(id) != expected) {
            if (response != null) {
                try {
                    response.body().close();
                } catch (IOException ignored) {
                    // The request was already cancelled or closed; there is nothing left to report.
                }
            }
            return;
        }
        if (failure != null) {
            Throwable cause = unwrap(failure);
            if (cause instanceof HttpTimeoutException) {
                completeExceptionally(id, expected,
                        new McpTimeoutException("MCP request timed out: " + method, cause));
            } else {
                failGeneration(expected.generation,
                        new McpUnavailableException("MCP HTTP service is unavailable", cause));
            }
            return;
        }
        try (InputStream body = response.body()) {
            validateResponseStatus(expected.generation, response.statusCode());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            JsonRpcResponse rpcResponse;
            if (isContentType(contentType, JSON_CONTENT_TYPE)) {
                rpcResponse = parseJsonResponse(body, id);
            } else if (isContentType(contentType, SSE_CONTENT_TYPE)) {
                rpcResponse = parseSseResponse(body, id);
            } else {
                throw new McpProtocolException("MCP HTTP response has an unsupported content type");
            }
            captureSession(method, expected.generation, response);
            completeResponse(id, expected, rpcResponse);
        } catch (McpUnavailableException e) {
            failGeneration(expected.generation, e);
        } catch (IOException e) {
            failGeneration(expected.generation,
                    new McpUnavailableException("Could not read MCP HTTP response", e));
        } catch (McpException e) {
            completeExceptionally(id, expected, e);
        } catch (RuntimeException e) {
            completeExceptionally(id, expected,
                    new McpProtocolException("Could not process MCP HTTP response", e));
        }
    }

    @Override
    public void notify(String method, JsonNode params) {
        Objects.requireNonNull(method, "method");
        long generation = liveGeneration();
        HttpRequest request = requestBuilder(config.handshakeTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(
                        codec.encode(new JsonRpcNotification(method, params)),
                        StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream ignored = response.body()) {
                validateResponseStatus(generation, response.statusCode());
            }
        } catch (HttpTimeoutException e) {
            throw new McpTimeoutException("MCP notification timed out: " + method, e);
        } catch (McpUnavailableException e) {
            failGeneration(generation, e);
            throw e;
        } catch (IOException e) {
            McpUnavailableException failure =
                    new McpUnavailableException("MCP HTTP service is unavailable", e);
            failGeneration(generation, failure);
            throw failure;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpUnavailableException("Interrupted while sending MCP notification", e);
        }
    }

    private HttpRequest.Builder requestBuilder(Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", JSON_CONTENT_TYPE)
                .header("Accept", JSON_CONTENT_TYPE + ", " + SSE_CONTENT_TYPE);
        String currentSession = sessionId;
        if (currentSession != null) {
            builder.header("Mcp-Session-Id", currentSession);
        }
        String currentProtocol = negotiatedProtocolVersion;
        if (currentProtocol != null) {
            builder.header("MCP-Protocol-Version", currentProtocol);
        }
        return builder;
    }

    private void validateResponseStatus(long generation, int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        if (statusCode == 404) {
            throw new McpUnavailableException("MCP HTTP session is unavailable");
        }
        if (statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500) {
            throw new McpUnavailableException("MCP HTTP service is temporarily unavailable");
        }
        if (generation != activeGeneration) {
            throw new McpUnavailableException("MCP HTTP session changed during request");
        }
        throw new McpProtocolException("MCP HTTP request was rejected");
    }

    private void captureSession(String method, long generation, HttpResponse<?> response) {
        if (!"initialize".equals(method) || generation != activeGeneration || !running.get()) {
            return;
        }
        response.headers().firstValue("Mcp-Session-Id")
                .filter(value -> !value.isBlank())
                .ifPresent(value -> sessionId = value);
    }

    private JsonRpcResponse parseJsonResponse(InputStream body, long expectedId) throws IOException {
        String json = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        JsonRpcMessage message = codec.decode(json);
        if (!(message instanceof JsonRpcResponse response) || !matches(response, expectedId)) {
            throw new McpProtocolException("MCP HTTP response id does not match the request");
        }
        return response;
    }

    private JsonRpcResponse parseSseResponse(InputStream body, long expectedId) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    JsonRpcResponse response = decodeSseEvent(data, expectedId);
                    data.setLength(0);
                    if (response != null) {
                        return response;
                    }
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    String value = line.substring(5);
                    data.append(value.startsWith(" ") ? value.substring(1) : value);
                }
            }
            JsonRpcResponse response = decodeSseEvent(data, expectedId);
            if (response != null) {
                return response;
            }
        }
        throw new McpProtocolException("MCP SSE stream ended without a matching response");
    }

    private JsonRpcResponse decodeSseEvent(StringBuilder data, long expectedId) {
        if (data.isEmpty()) {
            return null;
        }
        JsonRpcMessage message = codec.decode(data.toString());
        if (message instanceof JsonRpcResponse response && matches(response, expectedId)) {
            return response;
        }
        return null;
    }

    private static boolean matches(JsonRpcResponse response, long expectedId) {
        return response.id().canConvertToLong() && response.id().longValue() == expectedId;
    }

    private void completeResponse(long id, PendingRequest expected, JsonRpcResponse response) {
        if (!pending.remove(id, expected)) {
            return;
        }
        consecutiveTimeouts.set(0);
        if (response.error() != null) {
            expected.result.completeExceptionally(new McpRemoteException(response.error()));
        } else {
            JsonNode protocolVersion = response.result().get("protocolVersion");
            if ("initialize".equals(expected.method)
                    && protocolVersion != null && protocolVersion.isTextual()
                    && !protocolVersion.asText().isBlank()
                    && expected.generation == activeGeneration) {
                negotiatedProtocolVersion = protocolVersion.asText();
            }
            expected.result.complete(response.result());
        }
    }

    private void completeExceptionally(long id, PendingRequest expected, McpException failure) {
        if (pending.remove(id, expected)) {
            expected.result.completeExceptionally(failure);
        }
    }

    private void recordTimeout(long generation) {
        if (generation == activeGeneration && running.get()
                && consecutiveTimeouts.incrementAndGet()
                >= config.consecutiveTimeoutsBeforeRestart()) {
            failGeneration(generation,
                    new McpUnavailableException("MCP HTTP transport exceeded consecutive timeout limit"));
        }
    }

    private void failGeneration(long generation, McpUnavailableException failure) {
        if (generation != activeGeneration || !running.compareAndSet(true, false)) {
            return;
        }
        sessionId = null;
        negotiatedProtocolVersion = null;
        for (Map.Entry<Long, PendingRequest> entry : pending.entrySet()) {
            PendingRequest request = entry.getValue();
            if (request.generation == generation && pending.remove(entry.getKey(), request)) {
                request.cancelExchange();
                request.result.completeExceptionally(failure);
            }
        }
        if (!closed.get()) {
            failureListener.get().accept(failure);
        }
    }

    private long liveGeneration() {
        long generation = activeGeneration;
        if (closed.get() || !running.get()) {
            throw new McpUnavailableException("MCP HTTP transport is not available");
        }
        return generation;
    }

    private void acquirePermit(String method, long deadline) {
        try {
            long remaining = remainingNanos(deadline);
            if (remaining <= 0 || !inFlight.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                throw new McpTimeoutException(
                        "Timed out waiting for MCP concurrency permit: " + method);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpUnavailableException(
                    "Interrupted while waiting for MCP concurrency permit", e);
        }
    }

    @Override
    public boolean isRunning() {
        return !closed.get() && running.get();
    }

    @Override
    public int pendingCount() {
        return pending.size();
    }

    @Override
    public String threadNamePrefix() {
        return threadNamePrefix;
    }

    @Override
    public void failCurrent(String message, Throwable cause) {
        failGeneration(activeGeneration, new McpUnavailableException(message, cause));
    }

    @Override
    public void onTransportFailure(Consumer<McpUnavailableException> listener) {
        failureListener.set(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running.set(false);
        McpUnavailableException failure =
                new McpUnavailableException("MCP HTTP transport is closed");
        for (Map.Entry<Long, PendingRequest> entry : pending.entrySet()) {
            PendingRequest request = entry.getValue();
            if (pending.remove(entry.getKey(), request)) {
                request.cancelExchange();
                request.result.completeExceptionally(failure);
            }
        }
        httpClient.shutdownNow();
        httpExecutor.shutdownNow();
        try {
            httpClient.awaitTermination(Duration.ofSeconds(2));
            httpExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isContentType(String value, String expected) {
        int semicolon = value.indexOf(';');
        String mediaType = semicolon < 0 ? value : value.substring(0, semicolon);
        return expected.equalsIgnoreCase(mediaType.trim());
    }

    private static void validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    private static long deadlineAfter(Duration timeout) {
        long now = System.nanoTime();
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
        long deadline = now + nanos;
        return deadline < 0 && now > 0 ? Long.MAX_VALUE : deadline;
    }

    private static long remainingNanos(long deadline) {
        return deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - System.nanoTime();
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class PendingRequest {

        private final long generation;
        private final String method;
        private final CompletableFuture<JsonNode> result = new CompletableFuture<>();
        private volatile CompletableFuture<HttpResponse<InputStream>> exchange;

        private PendingRequest(long generation, String method) {
            this.generation = generation;
            this.method = method;
        }

        private void cancelExchange() {
            CompletableFuture<?> currentExchange = exchange;
            if (currentExchange != null) {
                currentExchange.cancel(true);
            }
        }
    }
}
