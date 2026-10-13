package dev.lifeagent.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Immutable process, protocol and resilience settings for an MCP stdio client.
 *
 * <p>Security posture: the MCP server is an external child process, frequently third-party code,
 * so by default it is handed an <em>empty</em> environment plus exactly the entries of
 * {@link #environment()}. The parent process environment is deliberately not inherited: it
 * routinely carries credentials (API tokens, database passwords, cloud keys) that have nothing to
 * do with the server and that a compromised - or merely curious - server would otherwise read
 * straight out of {@code System.getenv()}. Isolation is also the more predictable behaviour, since
 * the child then depends only on what the caller wrote down explicitly instead of on whatever the
 * launching shell happened to export.
 *
 * <p>A caller who genuinely needs the parent environment (for example a server that resolves
 * helper binaries through {@code PATH}) can opt back in explicitly with
 * {@link Builder#inheritParentEnvironment(boolean)}, which merges {@link #environment()} on top of
 * the inherited variables.
 *
 * @param inheritParentEnvironment {@code false} (the default) clears the child environment and then
 *     injects only {@link #environment()}; {@code true} starts from the parent environment and
 *     merges {@link #environment()} on top of it
 */
public record McpClientConfig(
        List<String> command,
        Map<String, String> environment,
        boolean inheritParentEnvironment,
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
        // inheritParentEnvironment is a primitive boolean: there is no null, aliasing or mutability
        // hazard to defend against the way there is for the collection components above. The
        // defensive part lives in Builder, where the field is initialised to false, so isolation is
        // what a caller gets without ever having to think about this flag.
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
        private boolean inheritParentEnvironment = false;
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

        /**
         * Opts in to giving the child process this process's environment, with
         * {@link #environment(Map)} merged on top of it.
         *
         * <p>The default is {@code false}: without this call the child is started with an empty
         * environment containing only the configured entries, so the parent's credentials and
         * configuration are not readable by the server. Turn it on only when the server really
         * needs an inherited variable (typically {@code PATH} or a toolchain variable), and prefer
         * injecting that single variable with {@link #environment(Map)} instead.
         *
         * @param inheritParentEnvironment {@code true} to inherit and merge, {@code false} to isolate
         * @return this builder
         */
        public Builder inheritParentEnvironment(boolean inheritParentEnvironment) {
            this.inheritParentEnvironment = inheritParentEnvironment;
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
            return new McpClientConfig(command, environment, inheritParentEnvironment,
                    maxConcurrentRequests, restartBackoff, consecutiveTimeoutsBeforeRestart,
                    handshakeTimeout, protocolVersion, clientName, clientVersion, stderrLogger);
        }
    }
}
