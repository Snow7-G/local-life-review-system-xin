package dev.lifeagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.function.Consumer;

/** Transport-independent JSON-RPC operations used by {@link McpClient}. */
public interface McpTransport extends AutoCloseable {

    void start();

    JsonNode request(String method, JsonNode params, Duration timeout);

    void notify(String method, JsonNode params);

    boolean isRunning();

    int pendingCount();

    String threadNamePrefix();

    void failCurrent(String message, Throwable cause);

    @Override
    void close();
}

/** Internal lifecycle signal kept separate so the public transport contract stays protocol-only. */
interface McpTransportFailureSource {

    void onTransportFailure(Consumer<McpUnavailableException> listener);
}
