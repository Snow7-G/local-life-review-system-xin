package dev.lifeagent.llm;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Immutable HTTP settings. Sensitive header values are supplied by the caller at runtime. */
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

    private static void validateEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        String scheme = endpoint.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || endpoint.getHost() == null) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URI");
        }
    }
}
