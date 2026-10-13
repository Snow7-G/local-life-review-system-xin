package dev.lifeagent.llm;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SseParserTest {

    @Test
    void handlesCommentsBlankLinesMultilineDataAndUtf8SplitAcrossEveryByte() throws Exception {
        String wire = ": keep-alive\r\n"
                + "\r\n"
                + "event: chunk\r\n"
                + "data: {\"text\":\"上海🙂\",\r\n"
                + "data: \"ok\":true}\r\n"
                + "\r\n";
        List<SseParser.SseEvent> events = new ArrayList<>();

        SseParser.parse(new OneByteAtATimeInputStream(
                wire.getBytes(StandardCharsets.UTF_8)), event -> {
            events.add(event);
            return true;
        });

        assertEquals(List.of(new SseParser.SseEvent(
                "chunk", "{\"text\":\"上海🙂\",\n\"ok\":true}")), events);
    }

    @Test
    void dispatchesFinalEventAtEndOfStreamWithoutTrailingBlankLine() throws Exception {
        List<SseParser.SseEvent> events = new ArrayList<>();

        SseParser.parse(new ByteArrayInputStream("data: [DONE]".getBytes(StandardCharsets.UTF_8)),
                event -> {
                    events.add(event);
                    return true;
                });

        assertEquals(List.of(new SseParser.SseEvent("message", "[DONE]")), events);
    }

    private static final class OneByteAtATimeInputStream extends ByteArrayInputStream {

        private OneByteAtATimeInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            return super.read(target, offset, Math.min(length, 1));
        }

        @Override
        public byte[] readAllBytes() {
            throw new AssertionError("streaming parser must not buffer the entire body as bytes");
        }
    }
}
