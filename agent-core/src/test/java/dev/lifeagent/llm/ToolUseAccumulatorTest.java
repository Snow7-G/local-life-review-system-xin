package dev.lifeagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolUseAccumulatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesTwoFragmentsSplitInsideAStringLiteral() {
        assertFragments(List.of("{\"city\":\"上", "海\"}"), "上海", null);
    }

    @Test
    void parsesFiveFragmentsIncludingASplitEscapeSequence() {
        assertFragments(List.of("{\"q\":", "\"a", "\\", "\"b", "\"}"), null, "a\"b");
    }

    @Test
    void parsesArbitrarilyManySingleCharacterFragments() {
        String json = "{\"city\":\"上海\",\"q\":\"火锅\"}";
        List<String> fragments = new ArrayList<>();
        json.chars().mapToObj(value -> Character.toString((char) value)).forEach(fragments::add);

        ToolUseAccumulator accumulator = new ToolUseAccumulator();
        accumulator.start("many", "search");
        fragments.forEach(fragment -> accumulator.append("many", fragment));
        JsonNode result = accumulator.complete("many").arguments();

        assertEquals("上海", result.path("city").asText());
        assertEquals("火锅", result.path("q").asText());
    }

    @Test
    void interleavedToolCallsNeverMixTheirFragments() {
        ToolUseAccumulator accumulator = new ToolUseAccumulator();
        accumulator.start("A", "first");
        accumulator.start("B", "second");
        accumulator.append("A", "{\"a\":");
        accumulator.append("B", "{\"b\":");
        accumulator.append("A", "1}");
        accumulator.append("B", "2}");

        assertEquals(2, accumulator.complete("B").arguments().path("b").asInt());
        assertEquals(1, accumulator.complete("A").arguments().path("a").asInt());
    }

    @Test
    void noFragmentsMeansAnEmptyObjectAtToolUseEnd() {
        ToolUseAccumulator accumulator = new ToolUseAccumulator();
        accumulator.start("empty", "no_args");

        JsonNode arguments = accumulator.complete("empty").arguments();

        assertTrue(arguments.isObject());
        assertTrue(arguments.isEmpty());
    }

    @Test
    void malformedCompleteJsonFailsClearlyAndNeverReturnsAPartialValue() {
        ToolUseAccumulator accumulator = new ToolUseAccumulator();
        accumulator.start("broken", "bad");
        accumulator.append("broken", "{\"a\":");

        LlmProtocolException failure = assertThrows(
                LlmProtocolException.class, () -> accumulator.complete("broken"));

        assertTrue(failure.getMessage().contains("broken"));
        assertTrue(failure.getMessage().contains("Invalid complete JSON"));
        assertThrows(IllegalStateException.class, () -> accumulator.arguments("broken"));
    }

    @Test
    void concatenatedJsonObjectsFailClearlyInsteadOfReturningTheFirstObject() {
        ToolUseAccumulator accumulator = new ToolUseAccumulator();
        accumulator.start("trailing", "search");
        accumulator.append("trailing", "{\"city\":\"上\"}");
        accumulator.append("trailing", "{\"city\":\"上海\"}");

        LlmProtocolException failure = assertThrows(
                LlmProtocolException.class, () -> accumulator.complete("trailing"));

        assertTrue(failure.getMessage().contains("trailing"));
        assertTrue(failure.getMessage().contains("Invalid complete JSON"));
        assertThrows(IllegalStateException.class, () -> accumulator.arguments("trailing"));
    }

    @Test
    void parserIsNeverInvokedAndArgumentsCannotBeReadBeforeToolUseEnd() throws Exception {
        AtomicInteger parseCalls = new AtomicInteger();
        ToolUseAccumulator accumulator = new ToolUseAccumulator(completeJson -> {
            parseCalls.incrementAndGet();
            return MAPPER.readTree(completeJson);
        });
        accumulator.accept(new ToolUseStart("guarded", "search"));
        accumulator.accept(new ToolUseArgumentsDelta("guarded", "{\"city\":\"上"));

        assertEquals(0, parseCalls.get(), "a partial fragment must never reach the JSON parser");
        IllegalStateException earlyRead = assertThrows(
                IllegalStateException.class, () -> accumulator.arguments("guarded"));
        assertTrue(earlyRead.getMessage().contains("before ToolUseEnd"));
        assertEquals(0, parseCalls.get(), "an early read must not trigger speculative parsing");

        accumulator.accept(new ToolUseArgumentsDelta("guarded", "海\"}"));
        assertEquals(0, parseCalls.get(), "even valid complete text waits for ToolUseEnd");
        accumulator.accept(new ToolUseEnd("guarded"));

        assertEquals(1, parseCalls.get(), "ToolUseEnd performs exactly one whole-document parse");
        assertEquals("上海", accumulator.arguments("guarded").path("city").asText());
        assertEquals(1, parseCalls.get(), "reading a completed value uses the stored parse result");
    }

    private static void assertFragments(
            List<String> fragments,
            String expectedCity,
            String expectedQuery) {
        ToolUseAccumulator accumulator = new ToolUseAccumulator();
        accumulator.start("split", "search");
        fragments.forEach(fragment -> accumulator.append("split", fragment));

        JsonNode result = accumulator.complete("split").arguments();

        if (expectedCity != null) {
            assertEquals(expectedCity, result.path("city").asText());
        }
        if (expectedQuery != null) {
            assertEquals(expectedQuery, result.path("q").asText());
        }
    }
    @Test
    void injectedLaxMapperStillRejectsTrailingContent() {
        // A caller-supplied mapper must not be able to reintroduce silent truncation:
        // the class owns this invariant, not the caller.
        ObjectMapper lax = new ObjectMapper();
        assertFalse(lax.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS),
                "precondition: the injected mapper really is lax");

        ToolUseAccumulator accumulator = new ToolUseAccumulator(lax);
        accumulator.start("lax-injected", "search");
        accumulator.append("lax-injected", "{\"city\":\"上\"}");
        accumulator.append("lax-injected", "{\"city\":\"上海\"}");

        assertThrows(LlmProtocolException.class, () -> accumulator.complete("lax-injected"));
    }

}
