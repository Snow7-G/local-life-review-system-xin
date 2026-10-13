package dev.lifeagent.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Incremental UTF-8 SSE framing. Provider-specific events never escape this package. */
final class SseParser {

    private SseParser() {
    }

    static void parse(InputStream input, EventHandler handler) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(handler, "handler");
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, decoder))) {
            EventBuilder event = new EventBuilder();
            boolean firstLine = true;
            String line;
            while ((line = reader.readLine()) != null) {
                if (firstLine && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                firstLine = false;
                if (line.isEmpty()) {
                    if (!dispatch(event, handler)) {
                        return;
                    }
                    event = new EventBuilder();
                    continue;
                }
                if (line.charAt(0) == ':') {
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                switch (field) {
                    case "event" -> event.event = value;
                    case "data" -> event.addData(value);
                    default -> {
                        // id, retry, and extension fields are irrelevant to one HTTP response.
                    }
                }
            }
            dispatch(event, handler);
        }
    }

    private static boolean dispatch(EventBuilder event, EventHandler handler) {
        if (!event.hasData) {
            return true;
        }
        return handler.onEvent(new SseEvent(
                event.event == null || event.event.isEmpty() ? "message" : event.event,
                event.data.toString()));
    }

    @FunctionalInterface
    interface EventHandler {
        boolean onEvent(SseEvent event);
    }

    record SseEvent(String event, String data) {
    }

    private static final class EventBuilder {

        private String event;
        private final StringBuilder data = new StringBuilder();
        private boolean hasData;

        private void addData(String value) {
            if (hasData) {
                data.append('\n');
            }
            data.append(value);
            hasData = true;
        }
    }
}
