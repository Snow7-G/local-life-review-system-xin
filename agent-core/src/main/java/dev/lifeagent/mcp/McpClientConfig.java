package dev.lifeagent.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Immutable process, protocol and resilience settings for an MCP stdio client. */
public record McpClientConfig(
        List<String> command,
        Map<String, String> environment,
        int maxConcurrentRequests,
        List<Duration> restartBackoff,
        int consecutiveTimeoutsBeforeRestart,
        Duration handshakeTimeout,
        String protocolVersion,
        String clientName,
        String clientVersion,
        Consumer<String> stderrLogger) {

    public McpClientConfig {
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        restartBackoff = List.copyOf(Objects.requireNonNull(restartBackoff, "restartBackoff"));
        Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        Objects.requireNonNull(clientName, "clientName");
        Objects.requireNonNull(clientVersion, "clientVersion");
        Objects.requireNonNull(stderrLogger, "stderrLogger");
        if (command.isEmpty() || command.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("command must not be empty or contain blank elements");
        }
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
    }

    private static boolean isNegative(Duration duration) {
        return duration == null || duration.isNegative();
    }

    public static Builder builder(List<String> command) {
        return new Builder(command);
    }

    public static final class Builder {

        private final List<String> command;
        private Map<String, String> environment = Map.of();
        private int maxConcurrentRequests = 16;
        private List<Duration> restartBackoff = List.of(
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30));
        private int consecutiveTimeoutsBeforeRestart = 3;
        private Duration handshakeTimeout = Duration.ofSeconds(10);
        private String protocolVersion = "2025-06-18";
        private String clientName = "lifeagent";
        private String clientVersion = "0.1.0";
        private Consumer<String> stderrLogger = line -> { };

        private Builder(List<String> command) {
            this.command = List.copyOf(Objects.requireNonNull(command, "command"));
        }

        public Builder environment(Map<String, String> environment) {
            this.environment = Map.copyOf(environment);
            return this;
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

        public Builder stderrLogger(Consumer<String> stderrLogger) {
            this.stderrLogger = stderrLogger;
            return this;
        }

        public McpClientConfig build() {
            return new McpClientConfig(command, environment, maxConcurrentRequests, restartBackoff,
                    consecutiveTimeoutsBeforeRestart, handshakeTimeout, protocolVersion,
                    clientName, clientVersion, stderrLogger);
        }
    }
}
