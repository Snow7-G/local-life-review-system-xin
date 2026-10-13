package dev.lifeagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * One-JSON-object-per-line transport over a managed child process. Responses are
 * dispatched by id, so callers may have multiple requests in flight at once.
 */
public final class StdioTransport implements AutoCloseable {

    @FunctionalInterface
    interface TerminationListener {
        void onTerminated(McpUnavailableException failure);
    }

    private static final AtomicLong TRANSPORT_SEQUENCE = new AtomicLong();

    private final McpClientConfig config;
    private final JsonRpcCodec codec;
    private final TerminationListener terminationListener;
    private final Semaphore inFlight;
    private final ConcurrentHashMap<Long, PendingRequest> pending = new ConcurrentHashMap<>();
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicInteger consecutiveTimeouts = new AtomicInteger();
    private final AtomicReference<ProcessSession> activeSession = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReentrantLock stdinLock = new ReentrantLock();
    private final Object lifecycleLock = new Object();
    private final String threadNamePrefix;

    StdioTransport(McpClientConfig config, JsonRpcCodec codec,
                   TerminationListener terminationListener) {
        this.config = Objects.requireNonNull(config, "config");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.terminationListener = Objects.requireNonNull(terminationListener, "terminationListener");
        this.inFlight = new Semaphore(config.maxConcurrentRequests(), true);
        this.threadNamePrefix = "mcp-stdio-" + TRANSPORT_SEQUENCE.incrementAndGet() + "-";
    }

    void start() {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new McpUnavailableException("MCP transport is closed");
            }
            ProcessSession existing = activeSession.get();
            if (existing != null && existing.process().isAlive()) {
                throw new McpUnavailableException("MCP process is already running");
            }

            ProcessBuilder builder = new ProcessBuilder(config.command());
            builder.redirectErrorStream(false);
            builder.environment().putAll(config.environment());
            try {
                Process process = builder.start();
                ProcessSession session = createSession(process);
                activeSession.set(session);
                consecutiveTimeouts.set(0);
                session.stdoutThread().start();
                session.stderrThread().start();
            } catch (IOException e) {
                throw new McpUnavailableException("Could not start MCP process", e);
            }
        }
    }

    JsonNode request(String method, JsonNode params, Duration timeout) {
        Objects.requireNonNull(method, "method");
        validateTimeout(timeout);
        long deadline = deadlineAfter(timeout);
        acquirePermit(method, deadline);
        long id = 0;
        PendingRequest pendingRequest = null;
        try {
            ProcessSession session = liveSession();
            id = requestIds.incrementAndGet();
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pendingRequest = new PendingRequest(session, future);
            pending.put(id, pendingRequest);
            write(session, new JsonRpcRequest(id, method, params));
            long remaining = remainingNanos(deadline);
            if (remaining <= 0) {
                throw new TimeoutException("request deadline elapsed while writing");
            }
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            if (pendingRequest != null) {
                pending.remove(id, pendingRequest);
                recordTimeout(pendingRequest.session());
            }
            throw new McpTimeoutException("MCP request timed out: " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (pendingRequest != null) {
                pending.remove(id, pendingRequest);
            }
            throw new McpUnavailableException("Interrupted while waiting for MCP response", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof McpException mcpException) {
                throw mcpException;
            }
            throw new McpUnavailableException("MCP request failed: " + method, cause);
        } finally {
            if (pendingRequest != null) {
                pending.remove(id, pendingRequest);
            }
            inFlight.release();
        }
    }

    void notify(String method, JsonNode params) {
        Objects.requireNonNull(method, "method");
        write(liveSession(), new JsonRpcNotification(method, params));
    }

    boolean isRunning() {
        ProcessSession session = activeSession.get();
        return !closed.get() && session != null && session.process().isAlive();
    }

    int pendingCount() {
        return pending.size();
    }

    String threadNamePrefix() {
        return threadNamePrefix;
    }

    void failCurrent(String message, Throwable cause) {
        ProcessSession session = activeSession.get();
        if (session != null) {
            failSession(session, new McpUnavailableException(message, cause));
        }
    }

    private ProcessSession createSession(Process process) {
        BufferedReader stdout = process.inputReader(StandardCharsets.UTF_8);
        BufferedReader stderr = process.errorReader(StandardCharsets.UTF_8);
        BufferedWriter stdin = process.outputWriter(StandardCharsets.UTF_8);
        AtomicReference<ProcessSession> holder = new AtomicReference<>();
        Thread stdoutThread = Thread.ofPlatform()
                .name(threadNamePrefix + "stdout")
                .daemon(true)
                .unstarted(() -> readStdout(holder.get()));
        Thread stderrThread = Thread.ofPlatform()
                .name(threadNamePrefix + "stderr")
                .daemon(true)
                .unstarted(() -> readStderr(holder.get()));
        ProcessSession session = new ProcessSession(
                process, stdout, stderr, stdin, stdoutThread, stderrThread);
        holder.set(session);
        return session;
    }

    private void readStdout(ProcessSession session) {
        McpUnavailableException failure = new McpUnavailableException("MCP process closed stdout");
        try {
            String line;
            while (!closed.get() && (line = session.stdout().readLine()) != null) {
                JsonRpcMessage message = codec.decode(line);
                if (message instanceof JsonRpcResponse response) {
                    dispatchResponse(session, response);
                }
            }
        } catch (IOException | RuntimeException e) {
            failure = new McpUnavailableException("MCP stdout reader failed", e);
        } finally {
            if (!closed.get()) {
                failSession(session, failure);
            }
        }
    }

    private void readStderr(ProcessSession session) {
        Consumer<String> logger = config.stderrLogger();
        try {
            String line;
            while (!closed.get() && (line = session.stderr().readLine()) != null) {
                try {
                    logger.accept(line);
                } catch (RuntimeException ignored) {
                    // A broken logging sink must never stop draining the child pipe.
                }
            }
        } catch (IOException ignored) {
            // stdout/process termination owns lifecycle failure reporting.
        }
    }

    private void dispatchResponse(ProcessSession session, JsonRpcResponse response) {
        if (!response.id().canConvertToLong()) {
            failSession(session, new McpUnavailableException("MCP response has a non-integral id"));
            return;
        }
        long id = response.id().longValue();
        PendingRequest request = pending.get(id);
        if (request == null || request.session() != session || !pending.remove(id, request)) {
            return;
        }
        consecutiveTimeouts.set(0);
        if (response.error() != null) {
            request.future().completeExceptionally(new McpRemoteException(response.error()));
        } else {
            request.future().complete(response.result());
        }
    }

    private void write(ProcessSession expectedSession, JsonRpcMessage message) {
        String line = codec.encode(message);
        stdinLock.lock();
        try {
            if (activeSession.get() != expectedSession || !expectedSession.process().isAlive()) {
                throw new McpUnavailableException("MCP process is not available");
            }
            expectedSession.stdin().write(line);
            expectedSession.stdin().newLine();
            expectedSession.stdin().flush();
        } catch (IOException e) {
            McpUnavailableException failure = new McpUnavailableException("Could not write to MCP stdin", e);
            failSession(expectedSession, failure);
            throw failure;
        } finally {
            stdinLock.unlock();
        }
    }

    private ProcessSession liveSession() {
        ProcessSession session = activeSession.get();
        if (closed.get() || session == null || !session.process().isAlive()) {
            throw new McpUnavailableException("MCP process is not available");
        }
        return session;
    }

    private void acquirePermit(String method, long deadline) {
        try {
            long remaining = remainingNanos(deadline);
            if (remaining <= 0 || !inFlight.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                throw new McpTimeoutException("Timed out waiting for MCP concurrency permit: " + method);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpUnavailableException("Interrupted while waiting for MCP concurrency permit", e);
        }
    }

    private void recordTimeout(ProcessSession session) {
        if (activeSession.get() == session
                && consecutiveTimeouts.incrementAndGet() >= config.consecutiveTimeoutsBeforeRestart()) {
            failSession(session, new McpUnavailableException("MCP process exceeded consecutive timeout limit"));
        }
    }

    private void failSession(ProcessSession session, McpUnavailableException failure) {
        if (!activeSession.compareAndSet(session, null)) {
            return;
        }
        completePending(session, failure);
        shutdownSession(session);
        if (!closed.get()) {
            terminationListener.onTerminated(failure);
        }
    }

    private void completePending(ProcessSession session, McpUnavailableException failure) {
        for (Map.Entry<Long, PendingRequest> entry : pending.entrySet()) {
            PendingRequest request = entry.getValue();
            if (request.session() == session && pending.remove(entry.getKey(), request)) {
                request.future().completeExceptionally(failure);
            }
        }
    }

    private void shutdownSession(ProcessSession session) {
        session.process().destroy();
        closeQuietly(session.stdin());
        try {
            if (!session.process().waitFor(300, TimeUnit.MILLISECONDS)) {
                session.process().destroyForcibly();
                session.process().waitFor(700, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.process().destroyForcibly();
        }
        closeQuietly(session.stdout());
        closeQuietly(session.stderr());
        session.stdoutThread().interrupt();
        session.stderrThread().interrupt();
        joinUnlessCurrent(session.stdoutThread());
        joinUnlessCurrent(session.stderrThread());
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best effort during process teardown.
        }
    }

    private static void joinUnlessCurrent(Thread thread) {
        if (thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        McpUnavailableException failure = new McpUnavailableException("MCP transport is closed");
        synchronized (lifecycleLock) {
            ProcessSession session = activeSession.getAndSet(null);
            if (session != null) {
                completePending(session, failure);
                shutdownSession(session);
            }
        }
        for (Map.Entry<Long, PendingRequest> entry : pending.entrySet()) {
            if (pending.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().future().completeExceptionally(failure);
            }
        }
    }

    private record PendingRequest(ProcessSession session, CompletableFuture<JsonNode> future) {
    }

    private record ProcessSession(
            Process process,
            BufferedReader stdout,
            BufferedReader stderr,
            BufferedWriter stdin,
            Thread stdoutThread,
            Thread stderrThread) {
    }
}
