package dev.lifeagent.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Immutable protocol and resilience settings shared by all MCP transports. */
public record McpClientConfig(
        int maxConcurrentRequests,
        List<Duration> restartBackoff,
        int consecutiveTimeoutsBeforeRestart,
        Duration handshakeTimeout,
        String protocolVersion,
        String clientName,
        String clientVersion) {

    public McpClientConfig {
        restartBackoff = List.copyOf(Objects.requireNonNull(restartBackoff, "restartBackoff"));
        Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        Objects.requireNonNull(clientName, "clientName");
        Objects.requireNonNull(clientVersion, "clientVersion");
        if (maxConcurrentRequests < 1) {
            throw new IllegalArgumentException("maxConcurrentRequests must be positive");
        }
        if (restartBackoff.isEmpty() || restartBackoff.stream().anyMatch(McpClientConfig::isNegative)) {
            throw new IllegalArgumentException("restartBackoff must contain non-negative durations");
        }
        if (consecutiveTimeoutsBeforeRestart < 1) {
            throw new IllegalArgumentException("consecutiveTimeoutsBeforeRestart must be positive");
        }
        if (handshakeTimeout.isZero() || handshakeTimeout.isNegative()) {
            throw new IllegalArgumentException("handshakeTimeout must be positive");
        }
        if (protocolVersion.isBlank()) {
            throw new IllegalArgumentException("protocolVersion must not be blank");
        }
        if (clientName.isBlank() || clientVersion.isBlank()) {
            throw new IllegalArgumentException("client name and version must not be blank");
        }
    }

    private static boolean isNegative(Duration duration) {
        return duration == null || duration.isNegative();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private int maxConcurrentRequests = 16;
        private List<Duration> restartBackoff = List.of(
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30));
        private int consecutiveTimeoutsBeforeRestart = 3;
        private Duration handshakeTimeout = Duration.ofSeconds(10);
        private String protocolVersion = "2025-06-18";
        private String clientName = "lifeagent";
        private String clientVersion = "0.1.0";

        private Builder() {
        }

        public Builder maxConcurrentRequests(int maxConcurrentRequests) {
            this.maxConcurrentRequests = maxConcurrentRequests;
            return this;
        }

        public Builder restartBackoff(List<Duration> restartBackoff) {
            this.restartBackoff = List.copyOf(restartBackoff);
            return this;
        }

        public Builder consecutiveTimeoutsBeforeRestart(int count) {
            this.consecutiveTimeoutsBeforeRestart = count;
            return this;
        }

        public Builder handshakeTimeout(Duration handshakeTimeout) {
            this.handshakeTimeout = handshakeTimeout;
            return this;
        }

        public Builder protocolVersion(String protocolVersion) {
            this.protocolVersion = protocolVersion;
            return this;
        }

        public Builder clientInfo(String name, String version) {
            this.clientName = name;
            this.clientVersion = version;
            return this;
        }

        public McpClientConfig build() {
            return new McpClientConfig(maxConcurrentRequests, restartBackoff,
                    consecutiveTimeoutsBeforeRestart, handshakeTimeout, protocolVersion,
                    clientName, clientVersion);
        }
    }
}
