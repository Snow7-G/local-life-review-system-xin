package dev.lifeagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.lifeagent.llm.AnthropicMessagesAdapter;
import dev.lifeagent.llm.LlmClient;
import dev.lifeagent.llm.LlmHttpConfig;
import dev.lifeagent.llm.LlmProtocolException;
import dev.lifeagent.llm.LlmRequest;
import dev.lifeagent.llm.LlmStreamEvent;
import dev.lifeagent.llm.Message;
import dev.lifeagent.llm.MessageDelta;
import dev.lifeagent.llm.OpenAiCompatibleAdapter;
import dev.lifeagent.llm.RetryPolicy;
import dev.lifeagent.llm.Role;
import dev.lifeagent.llm.StopReason;
import dev.lifeagent.llm.TextBlock;
import dev.lifeagent.llm.TextDelta;
import dev.lifeagent.llm.TokenUsage;
import dev.lifeagent.llm.ToolUseArgumentsDelta;
import dev.lifeagent.llm.ToolUseEnd;
import dev.lifeagent.llm.ToolUseStart;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the W2-1 review hardening items owned by this card.
 *
 * <p>They exercise {@code dev.lifeagent.llm} but live in {@code dev.lifeagent.tool} because this
 * card's test allowlist is {@code agent-core/src/test/java/dev/lifeagent/tool/**} and the {@code llm}
 * sources themselves must not be refactored for testability.
 *
 * <p>Covered: (1) a missing {@code [DONE]} sentinel, (2) the Anthropic provider error detail,
 * (3) the masked {@link LlmHttpConfig#toString()}, (4) the headers actually sent, (5) the
 * {@link MessageDelta} contract. Item (6) - the missing SSE read deadline - is deliberately a pair of
 * TODO comments only: the deadline belongs to the W2-3 loop layer and is not testable from here.
 */
class LlmHardeningTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String OPENAI_ENDPOINT = "https://example.test/v1/chat/completions";
    private static final String ANTHROPIC_ENDPOINT = "https://example.test/v1/messages";

    /** Assembled at runtime so the repository holds no plaintext-looking credential literal. */
    private static final String CREDENTIAL = "sk-" + "fixture" + "-" + "0".repeat(12);

    private static final String TRUNCATED_MESSAGE = "OpenAI SSE stream ended before [DONE]";

    // ------------------------------------------------------------------ (1) the [DONE] sentinel

    @Test
    void openAiAcceptsAMissingDoneSentinelWhenTheCompletionAlreadyFinished() {
        RecordingHttpClient http = RecordingHttpClient.sse(
                data(chunk(0, "hello", "stop")) + data(usageOnly(3, 4)));
        List<LlmStreamEvent> events = new ArrayList<>();

        openAi(config(OPENAI_ENDPOINT, Map.of("X-Test", "test")), http).stream(request(), events::add);

        assertEquals(List.of(
                new TextDelta("hello"),
                new MessageDelta(StopReason.END_TURN, null),
                new MessageDelta(null, new TokenUsage(3, 4))), events);
    }

    @Test
    void openAiAcceptsAMissingDoneSentinelAfterAToolCallFinished() {
        RecordingHttpClient http = RecordingHttpClient.sse(
                data(toolCallChunk(0, 0, "call_1", "{\"category\":\"火锅\"}", null))
                        + data(chunk(0, null, "tool_calls")));
        List<LlmStreamEvent> events = new ArrayList<>();

        openAi(config(OPENAI_ENDPOINT, Map.of("X-Test", "test")), http).stream(request(), events::add);

        assertEquals(4, events.size(), () -> "unexpected events: " + events);
        assertEquals(new ToolUseStart("call_1", "search"), events.get(0));
        assertEquals(new ToolUseArgumentsDelta("call_1", "{\"category\":\"火锅\"}"), events.get(1));
        assertEquals(new ToolUseEnd("call_1"), events.get(2));
        // finish_reason="tool_calls" must still surface as a stop reason even without [DONE].
        assertEquals(new MessageDelta(StopReason.TOOL_USE, null), events.get(3));
    }

    @Test
    void openAiStillRejectsATruncatedStreamWithoutAnyFinishReason() {
        RecordingHttpClient http = RecordingHttpClient.sse(data(chunk(0, "half an answer", null)));

        LlmProtocolException failure = assertThrows(
                LlmProtocolException.class,
                () -> openAi(config(OPENAI_ENDPOINT, Map.of("X-Test", "test")), http)
                        .stream(request(), ignored -> { }));

        assertEquals(TRUNCATED_MESSAGE, failure.getMessage());
    }

    @Test
    void openAiStillRejectsAMissingDoneSentinelWhileAToolCallIsStillPending() {
        // Choice 0 finishes, but choice 1 is still streaming tool arguments when the socket closes.
        RecordingHttpClient http = RecordingHttpClient.sse(
                data(toolCallChunk(0, 0, "call_1", "{\"category\"", null))
                        + data(chunk(0, null, "tool_calls"))
                        + data(toolCallChunk(1, 0, "call_2", "{\"radius\"", null)));

        LlmProtocolException failure = assertThrows(
                LlmProtocolException.class,
                () -> openAi(config(OPENAI_ENDPOINT, Map.of("X-Test", "test")), http)
                        .stream(request(), ignored -> { }));

        assertEquals(TRUNCATED_MESSAGE, failure.getMessage());
    }

    @Test
    void openAiStillAcceptsTheDoneSentinelAndFinishesEveryPendingToolCall() {
        RecordingHttpClient http = RecordingHttpClient.sse(
                data(toolCallChunk(0, 0, "call_1", "{\"category\"", null))
                        + "data: [DONE]\n\n");
        List<LlmStreamEvent> events = new ArrayList<>();

        openAi(config(OPENAI_ENDPOINT, Map.of("X-Test", "test")), http).stream(request(), events::add);

        assertEquals(List.of(
                new ToolUseStart("call_1", "search"),
                new ToolUseArgumentsDelta("call_1", "{\"category\""),
                new ToolUseEnd("call_1")), events);
    }

    // ----------------------------------------------------------------- (2) Anthropic error detail

    @Test
    void anthropicErrorEventsCarryTheProviderDetail() {
        RecordingHttpClient http = RecordingHttpClient.sse(
                anthropicEvent("error", anthropicError("overloaded_error", "Overloaded")));

        LlmProtocolException failure = assertThrows(
                LlmProtocolException.class,
                () -> anthropic(config(ANTHROPIC_ENDPOINT, Map.of("X-Test", "test")), http)
                        .stream(request(), ignored -> { }));

        assertEquals(
                "Anthropic SSE stream reported an error: overloaded_error - Overloaded",
                failure.getMessage());
    }

    @Test
    void anthropicErrorDetailIsSingleLineAndBounded() {
        RecordingHttpClient http = RecordingHttpClient.sse(anthropicEvent(
                "error", anthropicError("api_error", "x".repeat(4_000) + "\n\nsecond line")));

        LlmProtocolException failure = assertThrows(
                LlmProtocolException.class,
                () -> anthropic(config(ANTHROPIC_ENDPOINT, Map.of("X-Test", "test")), http)
                        .stream(request(), ignored -> { }));

        String message = failure.getMessage();
        assertTrue(message.length() < 600, () -> "detail must stay bounded, was " + message.length());
        assertTrue(message.endsWith("..."), () -> "a truncated detail must say so: " + message);
        assertFalse(message.contains("\n"), "the detail must be collapsed to one line");
    }

    @Test
    void anthropicErrorEventsWithoutDetailStillSaySomethingUseful() {
        RecordingHttpClient http = RecordingHttpClient.sse(
                anthropicEvent("error", MAPPER.createObjectNode().put("type", "error")));

        LlmProtocolException failure = assertThrows(
                LlmProtocolException.class,
                () -> anthropic(config(ANTHROPIC_ENDPOINT, Map.of("X-Test", "test")), http)
                        .stream(request(), ignored -> { }));

        assertTrue(failure.getMessage().contains("no detail supplied by the provider"),
                () -> "unexpected message: " + failure.getMessage());
    }

    // ------------------------------------------------------------ (3) masked LlmHttpConfig string

    @Test
    void configToStringPrintsHeaderNamesButNeverHeaderValues() {
        LlmHttpConfig config = new LlmHttpConfig(
                URI.create(OPENAI_ENDPOINT),
                Map.of("X-Api-Key", CREDENTIAL, "Authorization", "Bearer " + CREDENTIAL),
                Duration.ofSeconds(5),
                RetryPolicy.defaults());

        String printed = config.toString();

        assertTrue(printed.contains("Authorization"), () -> "header name missing from " + printed);
        assertTrue(printed.contains("X-Api-Key"), () -> "header name missing from " + printed);
        assertFalse(printed.contains(CREDENTIAL), () -> "the key value leaked: " + printed);
        assertFalse(printed.contains("Bearer"), () -> "the key value leaked: " + printed);
        assertTrue(printed.indexOf("Authorization") < printed.indexOf("X-Api-Key"),
                () -> "header names must come out in a stable order: " + printed);
        assertTrue(printed.contains(OPENAI_ENDPOINT), "the endpoint is not a secret");
    }

    // ------------------------------------------------------------------- (4) headers on the wire

    @Test
    void streamingRequestsCarryTheRequiredAndConfiguredHeaders() {
        RecordingHttpClient http = RecordingHttpClient.sse(anthropicDoneWire());
        LlmHttpConfig config = config(ANTHROPIC_ENDPOINT, Map.of("X-Tenant", "team-42"));

        anthropic(config, http).stream(request(), ignored -> { });
        HttpRequest sent = http.onlyRequest();

        assertEquals("POST", sent.method());
        assertEquals("application/json; charset=utf-8",
                sent.headers().firstValue("Content-Type").orElse(""));
        assertEquals("text/event-stream", sent.headers().firstValue("Accept").orElse(""));
        assertEquals("team-42", sent.headers().firstValue("X-Tenant").orElse(""));
        assertEquals(ANTHROPIC_ENDPOINT, sent.uri().toString());
        assertEquals(Duration.ofSeconds(5), sent.timeout().orElseThrow());
    }

    @Test
    void bothAdaptersSendTheSameRequiredHeadersToTheirOwnEndpoint() {
        RecordingHttpClient openAiHttp = RecordingHttpClient.sse(data(chunk(0, "hi", "stop")));
        RecordingHttpClient anthropicHttp = RecordingHttpClient.sse(anthropicDoneWire());
        Map<String, String> headers = Map.of("X-Tenant", "team-42");

        openAi(config(OPENAI_ENDPOINT, headers), openAiHttp).stream(request(), ignored -> { });
        anthropic(config(ANTHROPIC_ENDPOINT, headers), anthropicHttp).stream(request(), ignored -> { });

        for (HttpRequest sent : List.of(openAiHttp.onlyRequest(), anthropicHttp.onlyRequest())) {
            assertEquals("application/json; charset=utf-8",
                    sent.headers().firstValue("Content-Type").orElse(""));
            assertEquals("text/event-stream", sent.headers().firstValue("Accept").orElse(""));
            assertEquals("team-42", sent.headers().firstValue("X-Tenant").orElse(""));
        }
        assertEquals(OPENAI_ENDPOINT, openAiHttp.onlyRequest().uri().toString());
        assertEquals(ANTHROPIC_ENDPOINT, anthropicHttp.onlyRequest().uri().toString());
    }

    @Test
    void theConfiguredAuthorizationHeaderIsSentWithItsSchemeButNeverPrinted() {
        String authorization = "Bearer " + CREDENTIAL;
        LlmHttpConfig config = config(OPENAI_ENDPOINT, Map.of("Authorization", authorization));
        RecordingHttpClient http = RecordingHttpClient.sse(data(chunk(0, "hi", "stop")));

        openAi(config, http).stream(request(), ignored -> { });

        String sentValue = http.onlyRequest().headers().firstValue("Authorization").orElse("");
        assertTrue(sentValue.startsWith("Bearer "), "the scheme prefix must survive the round trip");
        assertEquals(authorization, sentValue);
        assertFalse(config.toString().contains(CREDENTIAL),
                "the masked config string must never carry the key value");
    }

    // ------------------------------------------------------------- (5) the MessageDelta javadoc

    @Test
    void messageDeltaAllowsEitherFieldToBeAbsentButNotBoth() {
        assertEquals(StopReason.END_TURN, new MessageDelta(StopReason.END_TURN, null).stopReason());
        assertEquals(new TokenUsage(1, 2), new MessageDelta(null, new TokenUsage(1, 2)).usage());
        assertThrows(IllegalArgumentException.class, () -> new MessageDelta(null, null));
    }

    // ------------------------------------------------------------------------------- fixtures

    private static LlmClient openAi(LlmHttpConfig config, RecordingHttpClient http) {
        return new OpenAiCompatibleAdapter(config, http, duration -> { }, () -> 0.0);
    }

    private static LlmClient anthropic(LlmHttpConfig config, RecordingHttpClient http) {
        return new AnthropicMessagesAdapter(config, http, duration -> { }, () -> 0.0);
    }

    private static LlmHttpConfig config(String endpoint, Map<String, String> headers) {
        return new LlmHttpConfig(
                URI.create(endpoint), headers, Duration.ofSeconds(5), RetryPolicy.defaults());
    }

    private static LlmRequest request() {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("type", "function");
        tool.set("function", MAPPER.createObjectNode()
                .put("name", "search")
                .set("parameters", MAPPER.createObjectNode().put("type", "object")));
        return new LlmRequest(
                List.of(new Message(Role.USER, List.of(new TextBlock("找火锅")))),
                List.of(tool),
                "system",
                "test-model",
                256,
                0.1);
    }

    private static ObjectNode envelope(ArrayNode choices) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("choices", choices);
        return root;
    }

    private static ObjectNode choice(int index, ObjectNode delta, String finishReason) {
        ObjectNode choice = MAPPER.createObjectNode().put("index", index);
        choice.set("delta", delta);
        if (finishReason == null) {
            choice.putNull("finish_reason");
        } else {
            choice.put("finish_reason", finishReason);
        }
        return choice;
    }

    private static ObjectNode chunk(int choiceIndex, String content, String finishReason) {
        ObjectNode delta = MAPPER.createObjectNode();
        if (content != null) {
            delta.put("content", content);
        }
        return envelope(MAPPER.createArrayNode().add(choice(choiceIndex, delta, finishReason)));
    }

    private static ObjectNode toolCallChunk(
            int choiceIndex,
            int toolIndex,
            String id,
            String arguments,
            String finishReason) {
        ObjectNode delta = MAPPER.createObjectNode();
        ObjectNode call = MAPPER.createObjectNode().put("index", toolIndex);
        if (id != null) {
            call.put("id", id);
        }
        // A first tool-call delta must carry both stable identifiers before arguments.
        // Later fragments may omit them; these hardening cases model the first delta.
        call.set("function", MAPPER.createObjectNode()
                .put("name", "search")
                .put("arguments", arguments));
        delta.set("tool_calls", MAPPER.createArrayNode().add(call));
        return envelope(MAPPER.createArrayNode().add(choice(choiceIndex, delta, finishReason)));
    }

    private static ObjectNode usageOnly(int promptTokens, int completionTokens) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("choices", MAPPER.createArrayNode());
        root.set("usage", MAPPER.createObjectNode()
                .put("prompt_tokens", promptTokens)
                .put("completion_tokens", completionTokens));
        return root;
    }

    private static ObjectNode anthropicError(String type, String message) {
        ObjectNode root = MAPPER.createObjectNode().put("type", "error");
        ObjectNode error = MAPPER.createObjectNode();
        if (type != null) {
            error.put("type", type);
        }
        if (message != null) {
            error.put("message", message);
        }
        root.set("error", error);
        return root;
    }

    private static String anthropicDoneWire() {
        return anthropicEvent("message_stop", MAPPER.createObjectNode().put("type", "message_stop"));
    }

    private static String anthropicEvent(String name, JsonNode payload) {
        return "event: " + name + "\n" + data(payload);
    }

    private static String data(JsonNode payload) {
        try {
            return "data: " + MAPPER.writeValueAsString(payload) + "\n\n";
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
