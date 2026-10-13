package dev.lifeagent.mcp;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Immutable child-process settings used only by {@link StdioTransport}. */
public record StdioTransportConfig(
        List<String> command,
        Map<String, String> environment,
        boolean inheritParentEnvironment,
        Consumer<String> stderrLogger) {

    public StdioTransportConfig {
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        Objects.requireNonNull(stderrLogger, "stderrLogger");
        if (command.isEmpty() || command.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("command must not be empty or contain blank elements");
        }
    }

    public static Builder builder(List<String> command) {
        return new Builder(command);
    }

    public static final class Builder {

        private final List<String> command;
        private Map<String, String> environment = Map.of();
        private boolean inheritParentEnvironment;
        private Consumer<String> stderrLogger = line -> { };

        private Builder(List<String> command) {
            this.command = List.copyOf(Objects.requireNonNull(command, "command"));
        }

        public Builder environment(Map<String, String> environment) {
            this.environment = Map.copyOf(environment);
            return this;
        }

        /**
         * Opts in to inheriting the parent environment before configured entries are overlaid.
         * The default is isolation: only {@link #environment(Map)} entries reach the child.
         */
        public Builder inheritParentEnvironment(boolean inheritParentEnvironment) {
            this.inheritParentEnvironment = inheritParentEnvironment;
            return this;
        }

        public Builder stderrLogger(Consumer<String> stderrLogger) {
            this.stderrLogger = Objects.requireNonNull(stderrLogger, "stderrLogger");
            return this;
        }

        public StdioTransportConfig build() {
            return new StdioTransportConfig(
                    command, environment, inheritParentEnvironment, stderrLogger);
        }
    }
}
