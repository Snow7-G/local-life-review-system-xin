package dev.lifeagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmAdaptersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void anthropicAdapterEmitsOnlyUnifiedEventsAcrossOneByteUtf8Buffers() {
        StubHttpClient http = new StubHttpClient();
        String wire = anthropicWire();
        http.enqueueOneByteAtATime(200, "text/event-stream; charset=utf-8", wire);
        LlmClient client = new AnthropicMessagesAdapter(
                config(RetryPolicy.defaults()), http, duration -> { }, () -> 0.0);
        List<LlmStreamEvent> events = new ArrayList<>();

        client.stream(request(), events::add);

        assertEquals(List.of(
                new TextDelta("上海🙂"),
                new ToolUseStart("tool-A", "search"),
                new ToolUseArgumentsDelta("tool-A", "{\"city\":\"上"),
                new ToolUseArgumentsDelta("tool-A", "海\"}"),
                new ToolUseEnd("tool-A"),
                new MessageDelta(StopReason.TOOL_USE, new TokenUsage(5, 7))), events);
        ToolUseAccumulator accumulator = new ToolUseAccumulator();
        events.stream()
                .filter(event -> event instanceof ToolUseStart
                        || event instanceof ToolUseArgumentsDelta
                        || event instanceof ToolUseEnd)
                .forEach(accumulator::accept);
        assertEquals("上海", accumulator.arguments("tool-A").path("city").asText());
        assertEquals("/v1/messages", http.requests().getFirst().uri().getPath());
    }

    @Test
    void anthropicNonEmptyInitialInputFollowedByAnotherJsonObjectFailsClearly() {
        StubHttpClient http = new StubHttpClient();
        http.enqueue(200, "text/event-stream", anthropicTrailingJsonWire());
        LlmClient client = new AnthropicMessagesAdapter(
                config(RetryPolicy.defaults()), http, duration -> { }, () -> 0.0);
        ToolUseAccumulator accumulator = new ToolUseAccumulator();

        LlmProtocolException failure = assertThrows(
                LlmProtocolException.class,
                () -> client.stream(request(), event -> {
                    if (event instanceof ToolUseStart
                            || event instanceof ToolUseArgumentsDelta
                            || event instanceof ToolUseEnd) {
                        accumulator.accept(event);
                    }
                }));

        assertEquals(
                "Invalid complete JSON arguments for tool use tool-trailing",
                failure.getMessage());
        assertThrows(
                IllegalStateException.class,
                () -> accumulator.arguments("tool-trailing"));
    }

    @Test
    void openAiAdapterKeepsInterleavedToolIndexesSeparatedAndStopsAtDone() {
        StubHttpClient http = new StubHttpClient();
        http.enqueue(200, "text/event-stream", openAiWire());
        LlmClient client = new OpenAiCompatibleAdapter(
                config(RetryPolicy.defaults()), http, duration -> { }, () -> 0.0);
        List<LlmStreamEvent> events = new ArrayList<>();

        client.stream(request(), events::add);

        assertEquals(List.of(
                new ToolUseStart("A", "first"),
                new ToolUseArgumentsDelta("A", "{\"a\":"),
                new ToolUseStart("B", "second"),
                new ToolUseArgumentsDelta("B", "{\"b\":"),
                new ToolUseArgumentsDelta("B", "2}"),
                new ToolUseArgumentsDelta("A", "1}"),
                new ToolUseEnd("A"),
                new ToolUseEnd("B"),
                new MessageDelta(StopReason.TOOL_USE, null),
                new MessageDelta(null, new TokenUsage(11, 4))), events);
    }

    @Test
    void rateLimitAndOverloadUseInjectedExponentialBackoffWithoutSleeping() {
        StubHttpClient http = new StubHttpClient();
        http.enqueue(429, "application/json", "{}");
        http.enqueue(529, "application/json", "{}");
        http.enqueue(200, "text/event-stream", event("message_stop",
                MAPPER.createObjectNode().put("type", "message_stop")));
        RetryPolicy policy = new RetryPolicy(
                3, Duration.ofMillis(100), Duration.ofSeconds(2), 0.5);
        List<Duration> sleeps = new ArrayList<>();
        LlmClient client = new AnthropicMessagesAdapter(
                config(policy), http, sleeps::add, () -> 0.0);

        client.stream(request(), ignored -> { });

        assertEquals(3, http.requests().size());
        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200)), sleeps);
    }

    @Test
    void parameterErrorsAreNeverRetried() {
        StubHttpClient http = new StubHttpClient();
        http.enqueue(400, "application/json", "{\"error\":\"bad request\"}");
        List<Duration> sleeps = new ArrayList<>();
        LlmClient client = new OpenAiCompatibleAdapter(
                config(RetryPolicy.defaults()), http, sleeps::add, () -> 0.0);

        LlmHttpException failure = assertThrows(
                LlmHttpException.class, () -> client.stream(request(), ignored -> { }));

        assertEquals(400, failure.statusCode());
        assertEquals(1, http.requests().size());
        assertEquals(List.of(), sleeps);
    }

    @Test
    void truncatedStreamsFailInsteadOfLookingLikeSuccessfulCompletion() {
        StubHttpClient http = new StubHttpClient();
        http.enqueue(200, "text/event-stream", data(openAiChunk()));
        LlmClient client = new OpenAiCompatibleAdapter(
                config(RetryPolicy.defaults()), http, duration -> { }, () -> 0.0);

        LlmProtocolException failure = assertThrows(
                LlmProtocolException.class, () -> client.stream(request(), ignored -> { }));

        assertEquals("OpenAI SSE stream ended before [DONE]", failure.getMessage());
    }

    @Test
    void retryJitterStaysAboveExponentiallyGrowingLowerBound() {
        RetryPolicy policy = new RetryPolicy(
                3, Duration.ofMillis(100), Duration.ofSeconds(10), 0.5);

        assertEquals(Duration.ofMillis(100), policy.delayForRetry(0, 0.0));
        assertEquals(Duration.ofMillis(150), policy.delayForRetry(0, 1.0));
        assertEquals(Duration.ofMillis(200), policy.delayForRetry(1, 0.0));
        assertEquals(Duration.ofMillis(300), policy.delayForRetry(1, 1.0));
        assertEquals(Duration.ofMillis(400), policy.delayForRetry(2, 0.0));
    }

    @Test
    void adaptersSerializeTheSameImmutableRequestIntoTheirProviderShapes() {
        StubHttpClient http = new StubHttpClient();
        AnthropicMessagesAdapter anthropic = new AnthropicMessagesAdapter(
                config(RetryPolicy.defaults()), http, duration -> { }, () -> 0.0);
        OpenAiCompatibleAdapter openAi = new OpenAiCompatibleAdapter(
                config(RetryPolicy.defaults()), http, duration -> { }, () -> 0.0);

        JsonNode anthropicBody = anthropic.createRequestBody(request());
        JsonNode openAiBody = openAi.createRequestBody(request());

        assertEquals("test-model", anthropicBody.path("model").asText());
        assertEquals("system", anthropicBody.path("system").asText());
        assertEquals("user", anthropicBody.path("messages").get(0).path("role").asText());
        assertEquals(true, anthropicBody.path("stream").asBoolean());
        assertEquals("test-model", openAiBody.path("model").asText());
        assertEquals("system", openAiBody.path("messages").get(0).path("role").asText());
        assertEquals(true, openAiBody.path("stream_options").path("include_usage").asBoolean());
        assertInstanceOf(ArrayNode.class, openAiBody.path("tools"));
    }

    private static LlmHttpConfig config(RetryPolicy policy) {
        return new LlmHttpConfig(
                URI.create("https://example.test/v1/messages"),
                Map.of("X-Test-Header", "test"),
                Duration.ofSeconds(5),
                policy);
    }

    private static LlmRequest request() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "function");
        schema.set("function", MAPPER.createObjectNode()
                .put("name", "search")
                .set("parameters", MAPPER.createObjectNode().put("type", "object")));
        return new LlmRequest(
                List.of(new Message(Role.USER, List.of(new TextBlock("找火锅")))),
                List.of(schema),
                "system",
                "test-model",
                256,
                0.1);
    }

    private static String anthropicWire() {
        ObjectNode messageStart = MAPPER.createObjectNode().put("type", "message_start");
        messageStart.set("message", MAPPER.createObjectNode()
                .set("usage", MAPPER.createObjectNode().put("input_tokens", 5)));

        ObjectNode textStart = MAPPER.createObjectNode()
                .put("type", "content_block_start").put("index", 0);
        textStart.set("content_block", MAPPER.createObjectNode()
                .put("type", "text").put("text", "上海🙂"));

        ObjectNode textStop = MAPPER.createObjectNode()
                .put("type", "content_block_stop").put("index", 0);

        ObjectNode toolStart = MAPPER.createObjectNode()
                .put("type", "content_block_start").put("index", 1);
        toolStart.set("content_block", MAPPER.createObjectNode()
                .put("type", "tool_use")
                .put("id", "tool-A")
                .put("name", "search")
                .set("input", MAPPER.createObjectNode()));

        ObjectNode firstDelta = anthropicToolDelta(1, "{\"city\":\"上");
        ObjectNode secondDelta = anthropicToolDelta(1, "海\"}");
        ObjectNode toolStop = MAPPER.createObjectNode()
                .put("type", "content_block_stop").put("index", 1);

        ObjectNode messageDelta = MAPPER.createObjectNode().put("type", "message_delta");
        messageDelta.set("delta", MAPPER.createObjectNode().put("stop_reason", "tool_use"));
        messageDelta.set("usage", MAPPER.createObjectNode().put("output_tokens", 7));
        ObjectNode messageStop = MAPPER.createObjectNode().put("type", "message_stop");

        return ": keep alive\n\n"
                + event("message_start", messageStart)
                + event("content_block_start", textStart)
                + event("content_block_stop", textStop)
                + event("content_block_start", toolStart)
                + event("content_block_delta", firstDelta)
                + event("content_block_delta", secondDelta)
                + event("content_block_stop", toolStop)
                + event("message_delta", messageDelta)
                + event("message_stop", messageStop);
    }

    private static String anthropicTrailingJsonWire() {
        ObjectNode toolStart = MAPPER.createObjectNode()
                .put("type", "content_block_start").put("index", 0);
        toolStart.set("content_block", MAPPER.createObjectNode()
                .put("type", "tool_use")
                .put("id", "tool-trailing")
                .put("name", "search")
                .set("input", MAPPER.createObjectNode().put("city", "上")));
        ObjectNode toolDelta = anthropicToolDelta(0, "{\"city\":\"上海\"}");
        ObjectNode toolStop = MAPPER.createObjectNode()
                .put("type", "content_block_stop").put("index", 0);
        ObjectNode messageStop = MAPPER.createObjectNode().put("type", "message_stop");

        return event("content_block_start", toolStart)
                + event("content_block_delta", toolDelta)
                + event("content_block_stop", toolStop)
                + event("message_stop", messageStop);
    }

    private static ObjectNode anthropicToolDelta(int index, String partial) {
        ObjectNode root = MAPPER.createObjectNode()
                .put("type", "content_block_delta").put("index", index);
        root.set("delta", MAPPER.createObjectNode()
                .put("type", "input_json_delta").put("partial_json", partial));
        return root;
    }

    private static String openAiWire() {
        ObjectNode first = openAiChunk();
        ArrayNode firstCalls = (ArrayNode) first.path("choices").get(0)
                .path("delta").path("tool_calls");
        firstCalls.add(toolCall(0, "A", "first", "{\"a\":"));
        firstCalls.add(toolCall(1, "B", "second", "{\"b\":"));

        ObjectNode second = openAiChunk();
        ArrayNode secondCalls = (ArrayNode) second.path("choices").get(0)
                .path("delta").path("tool_calls");
        secondCalls.add(toolCall(1, null, null, "2}"));
        secondCalls.add(toolCall(0, null, null, "1}"));

        ObjectNode finish = openAiChunk();
        ((ObjectNode) finish.path("choices").get(0)).put("finish_reason", "tool_calls");
        ObjectNode usage = MAPPER.createObjectNode();
        usage.set("choices", MAPPER.createArrayNode());
        usage.set("usage", MAPPER.createObjectNode()
                .put("prompt_tokens", 11).put("completion_tokens", 4));

        return data(first) + data(second) + data(finish) + data(usage) + "data: [DONE]\n\n";
    }

    private static ObjectNode openAiChunk() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode choice = MAPPER.createObjectNode().put("index", 0);
        choice.set("delta", MAPPER.createObjectNode()
                .set("tool_calls", MAPPER.createArrayNode()));
        choice.putNull("finish_reason");
        root.set("choices", MAPPER.createArrayNode().add(choice));
        return root;
    }

    private static ObjectNode toolCall(
            int index,
            String id,
            String name,
            String arguments) {
        ObjectNode call = MAPPER.createObjectNode().put("index", index);
        if (id != null) {
            call.put("id", id);
        }
        ObjectNode function = MAPPER.createObjectNode();
        if (name != null) {
            function.put("name", name);
        }
        function.put("arguments", arguments);
        call.set("function", function);
        return call;
    }

    private static String event(String name, JsonNode data) {
        return "event: " + name + "\n" + data(data);
    }

    private static String data(JsonNode data) {
        try {
            return "data: " + MAPPER.writeValueAsString(data) + "\n\n";
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
