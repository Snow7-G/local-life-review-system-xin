package com.hmdp.agent.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lifeagent.event.AgentEvent;
import dev.lifeagent.event.AnswerEvent;
import dev.lifeagent.event.ConfirmRequiredEvent;
import dev.lifeagent.event.PlanEvent;
import dev.lifeagent.event.ThinkingEvent;
import dev.lifeagent.event.ToolCallEvent;
import dev.lifeagent.event.ToolResultEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins the six-event mapping and payload shapes, so a rename or a swapped case cannot pass unnoticed.
 *
 * <p>{@link AgentEventStream#name(AgentEvent)} and {@link AgentEventStream#payload(AgentEvent)} are the
 * pure core of the transport; the end-to-end frame text is asserted in
 * {@link AgentConversationSseTest}, which drives a real {@code SseEmitter} through MockMvc.</p>
 */
class AgentEventStreamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentEventStream stream = new AgentEventStream(MAPPER);

    @Test
    void everyEventTypeMapsToItsOwnName() {
        assertEquals("plan", AgentEventStream.name(
                new PlanEvent("按需调用工具：search_nearby", List.of("search_nearby"))));
        assertEquals("tool_call", AgentEventStream.name(
                new ToolCallEvent("c1", "search_nearby", AgentTransportFixtures.json("{\"radius\":500}"))));
        assertEquals("tool_result", AgentEventStream.name(
                new ToolResultEvent("c1", "search_nearby", false, AgentTransportFixtures.json("[]"))));
        assertEquals("thinking", AgentEventStream.name(new ThinkingEvent("正在思考")));
        assertEquals("confirm_required", AgentEventStream.name(
                new ConfirmRequiredEvent("c1", "book_shop", "是否确认？")));
        assertEquals("answer", AgentEventStream.name(new AnswerEvent("推荐顺时针火锅。")));
    }

    @Test
    void everyPayloadCarriesTheFieldsTheFrontendNeeds() throws Exception {
        assertEquals("按需调用工具：search_nearby", field(
                new PlanEvent("按需调用工具：search_nearby", List.of("search_nearby")),
                n -> n.path("summary").asText()));
        assertEquals("search_nearby", field(
                new ToolCallEvent("c1", "search_nearby", AgentTransportFixtures.json("{\"radius\":500}")),
                n -> n.path("toolName").asText()));
        assertEquals("c1", field(
                new ToolResultEvent("c1", "search_nearby", true, AgentTransportFixtures.json("{}")),
                n -> n.path("callId").asText()));
        assertEquals("正在思考", field(new ThinkingEvent("正在思考"), n -> n.path("text").asText()));
        assertEquals("book_shop", field(
                new ConfirmRequiredEvent("c1", "book_shop", "是否确认？"),
                n -> n.path("toolName").asText()));
        assertEquals("推荐顺时针火锅。", field(
                new AnswerEvent("推荐顺时针火锅。"), n -> n.path("text").asText()));
    }

    @Test
    void toolResultKeepsItsBooleanAndNestedJsonContent() throws Exception {
        ToolResultEvent event = new ToolResultEvent("c1", "search_nearby", true,
                AgentTransportFixtures.json("{\"message\":\"参数校验失败\"}"));

        JsonNode payload = stream.payload(event);

        assertEquals(true, payload.path("isError").asBoolean());
        assertEquals("参数校验失败", payload.path("content").path("message").asText());
        assertFalse(payload.path("content").isTextual(), "成功/失败的 JSON 内容必须保持结构化");
    }

    @Test
    void planPayloadCarriesTheStepsArray() {
        PlanEvent event = new PlanEvent("计划", List.of("search_nearby", "shop_detail"));

        JsonNode steps = stream.payload(event).path("steps");

        assertEquals(2, steps.size());
        assertEquals("search_nearby", steps.get(0).asText());
        assertEquals("shop_detail", steps.get(1).asText());
    }

    private String field(AgentEvent event, Function<JsonNode, String> extract) {
        return extract.apply(stream.payload(event));
    }
}
