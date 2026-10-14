package dev.lifeagent.llm;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable HTTP settings. Sensitive header values are supplied by the caller at runtime.
 *
 * <p>TODO(W2-3 循环层): {@code requestTimeout} only bounds connecting and waiting for the response
 * head. A provider that accepts the request and then keeps the SSE body open forever is not bounded
 * by it, so the loop layer that owns the stream must add its own idle read deadline. Deliberately
 * not implemented here - this card must not grow a loop layer.</p>
 */
public record LlmHttpConfig(
        URI endpoint,
        Map<String, String> headers,
        Duration requestTimeout,
        RetryPolicy retryPolicy) {

    public LlmHttpConfig {
        validateEndpoint(endpoint);
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        headers.forEach((name, value) -> {
            if (name == null || name.isBlank() || value == null) {
                throw new IllegalArgumentException("header names and values must be non-null");
            }
        });
    }

    public LlmHttpConfig(URI endpoint, Map<String, String> headers) {
        this(endpoint, headers, Duration.ofMinutes(2), RetryPolicy.defaults());
    }
    /**
     * Prints the header <em>names</em> only: header values are credentials, so they never appear in
     * logs, assertion messages or stack traces. The order is sorted, because {@link Map#copyOf} does
     * not promise one.
     *
     * @return a representation that is safe to log
     */
    @Override
    public String toString() {
        return "LlmHttpConfig[endpoint=" + endpoint
                + ", headerNames=" + headers.keySet().stream().sorted().toList()
                + ", requestTimeout=" + requestTimeout
                + ", retryPolicy=" + retryPolicy
                + "]";
    }

    private static void validateEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        String scheme = endpoint.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || endpoint.getHost() == null) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URI");
        }
    }
}
