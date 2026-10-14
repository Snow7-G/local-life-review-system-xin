package com.hmdp.agent.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.transport.AgentTransportFixtures.CountingCatalog;
import com.hmdp.agent.transport.AgentTransportFixtures.ScriptedLlm;
import com.hmdp.agent.transport.AgentTransportFixtures.Turn;
import dev.lifeagent.hook.HookChain;
import dev.lifeagent.loop.AgentLoop;
import dev.lifeagent.loop.InMemorySessionStore;
import dev.lifeagent.loop.LoopSession;
import dev.lifeagent.loop.PermissionDecision;
import dev.lifeagent.loop.PermissionGate;
import dev.lifeagent.loop.SessionStore;
import dev.lifeagent.tool.Permission;
import dev.lifeagent.tool.ShopCatalog;
import dev.lifeagent.tool.ShopDetailArgs;
import dev.lifeagent.tool.ShopTools;
import dev.lifeagent.tool.ToolDefinition;
import dev.lifeagent.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CharacterEncodingFilter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * Drives the SSE endpoint with MockMvc and a scripted {@code LlmClient}, so no test reaches a model.
 *
 * <p>MockMvc buffers everything an {@code SseEmitter} sends before the async request is initialised
 * and flushes it when the response completes, which is exactly what these tests need: after
 * {@code getAsyncResult()} the whole frame list is available to assert on.</p>
 */
class AgentConversationSseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockMvc mvc;
    private SessionStore sessionStore;
    private CountingCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new CountingCatalog();
        sessionStore = new InMemorySessionStore();
        mvc = null;
    }

    private void wire(AgentLoopFactory factory) {
        AgentEventStream stream = new AgentEventStream(MAPPER);
        AgentTransportSettings settings = new AgentTransportSettings("test-model", "默认系统提示");
        AgentConversationController controller =
                new AgentConversationController(factory, sessionStore, stream, MAPPER, settings);
        // Boot registers a UTF-8 CharacterEncodingFilter in production; standalone MockMvc does not,
        // and without it the mock response forces ISO-8859-1 and every Chinese payload becomes "?".
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CharacterEncodingFilter("UTF-8", true))
                .build();
    }

    /** A factory wired with the read-only shop tools, a deny-all gate and one shared store. */
    private AgentLoopFactory readOnlyFactory(ScriptedLlm llm) {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(catalog));
        return factory(llm, registry, PermissionGate.denyAll(), sessionStore);
    }

    private static AgentLoopFactory factory(
            ScriptedLlm llm, ToolRegistry registry, PermissionGate gate, SessionStore store) {
        HookChain hooks = HookChain.empty();
        AgentLoop breakerSource = AgentLoop.create(llm, registry, hooks, gate);
        return sink -> new AgentLoop(llm, registry, hooks, gate, store, breakerSource.breaker(), sink);
    }

    // ------------------------------------------------------------------ 1. 正常对话

    @Test
    void normalTurnEmitsPlanCallResultAnswerInOrder() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(
                Turn.tools(AgentTransportFixtures.call("c1", "search_nearby",
                        "{\"category\":\"火锅\",\"radius\":500}")),
                Turn.answer("推荐顺时针火锅。"));
        wire(readOnlyFactory(llm));

        List<Frame> frames = stream("/agent/chat/stream", Map.of("message", "附近有什么火锅"));

        assertEquals(List.of("plan", "tool_call", "tool_result", "answer"), names(frames));
        assertEquals(1, catalog.searchNearbyCalls.get(), "只读工具应恰好执行一次");
    }

    // ------------------------------------------------------------------ 2. 挂起

    @Test
    void deferringGateEmitsConfirmRequiredAndEndsTheStream() throws Exception {
        AtomicInteger bookings = new AtomicInteger();
        ToolRegistry registry = registryWithBookingTool(bookings);
        ScriptedLlm llm = new ScriptedLlm(
                Turn.tools(AgentTransportFixtures.call("c1", "book_shop", "{\"shopId\":1}")));
        wire(factory(llm, registry, alwaysDefer("是否确认预约？"), sessionStore));

        List<Frame> frames = stream("/agent/chat/stream", Map.of("message", "帮我订座"));

        assertEquals(List.of("plan", "tool_call", "confirm_required"), names(frames));
        Frame confirm = frames.get(frames.size() - 1);
        assertEquals("book_shop", json(confirm).path("toolName").asText());
        assertEquals("c1", json(confirm).path("callId").asText());
        assertEquals("是否确认预约？", json(confirm).path("reason").asText());
        assertTrue(names(frames).stream().noneMatch("answer"::equals), "挂起时不应有 answer");
        assertEquals(0, bookings.get(), "挂起的写工具不得执行");

        LoopSession stored = sessionStore.load(AgentConversationController.DEFAULT_SESSION_ID).orElseThrow();
        assertTrue(stored.isWaitingForUser(), "会话应已挂起并存入 store");
        assertEquals("c1", stored.pendingPermission().request().callId());
    }

    // ------------------------------------------------------------------ 3. 恢复

    @Test
    void resumeContinuesFromTheStoredSessionWithoutReRunningFinishedToolCalls() throws Exception {
        AtomicInteger bookings = new AtomicInteger();
        ToolRegistry registry = registryWithBookingTool(bookings);
        // Turn 1 asks for a read then a write; the write's gate defers, so the read has already run
        // and its result is in the stored history. Turn 2 is the model's final answer.
        ScriptedLlm llm = new ScriptedLlm(
                Turn.tools(
                        AgentTransportFixtures.call("c0", "search_nearby",
                                "{\"category\":\"火锅\",\"radius\":500}"),
                        AgentTransportFixtures.call("c1", "book_shop", "{\"shopId\":1}")),
                Turn.answer("已按你的确认处理。"));
        AtomicBoolean deferred = new AtomicBoolean(false);
        PermissionGate gate = (context, call, permission) ->
                deferred.compareAndSet(false, true)
                        ? PermissionDecision.defer("是否确认预约？")
                        : PermissionDecision.allow();
        wire(factory(llm, registry, gate, sessionStore));

        List<Frame> first = stream("/agent/chat/stream", Map.of("message", "帮我找家火锅并订座", "sessionId", "s1"));
        assertEquals(List.of("plan", "tool_call", "tool_result", "tool_call", "confirm_required"),
                names(first));
        assertEquals(1, catalog.searchNearbyCalls.get());

        List<Frame> resumed = stream("/agent/chat/resume", Map.of("sessionId", "s1", "reply", "确认"));

        assertEquals(List.of("tool_result", "answer"), names(resumed));
        assertEquals("c1", json(resumed.get(0)).path("callId").asText());
        // The finished read tool must not run a second time, and the write runs exactly once.
        assertEquals(1, catalog.searchNearbyCalls.get(), "已完成的只读调用不得被重跑");
        assertEquals(1, bookings.get(), "写工具在恢复后应恰好执行一次");
    }

    @Test
    void resumedToolCallIsEmittedExactlyOnceAcrossBothStreams() throws Exception {
        AtomicInteger bookings = new AtomicInteger();
        ToolRegistry registry = registryWithBookingTool(bookings);
        ScriptedLlm llm = new ScriptedLlm(
                Turn.tools(AgentTransportFixtures.call("c1", "book_shop", "{\"shopId\":1}")),
                Turn.answer("已确认。"));
        AtomicBoolean deferred = new AtomicBoolean(false);
        PermissionGate gate = (context, call, permission) ->
                deferred.compareAndSet(false, true)
                        ? PermissionDecision.defer("是否确认预约？")
                        : PermissionDecision.allow();
        wire(factory(llm, registry, gate, sessionStore));

        List<Frame> first = stream("/agent/chat/stream", Map.of("message", "订座", "sessionId", "s2"));
        List<Frame> resumed = stream("/agent/chat/resume", Map.of("sessionId", "s2", "reply", "好"));

        List<Frame> all = new ArrayList<>(first);
        all.addAll(resumed);
        assertEquals(1, countToolCallsFor(all, "c1"), "同一 callId 的 tool_call 只能出现一次");
        assertEquals(1, countToolResultsFor(all, "c1"), "同一 callId 的 tool_result 只能出现一次");
    }

    // ------------------------------------------------------------------ 4. 错误路径

    @Test
    void llmFailureBecomesAReadableErrorFrameNot500() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(Turn.failing());
        wire(readOnlyFactory(llm));

        MvcResult result = mvc.perform(get("/agent/chat/stream").param("message", "你好"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(10_000);

        MockHttpServletResponse response = result.getResponse();
        assertEquals(200, response.getStatus(), "错误路径不得变成 500");
        List<Frame> frames = parse(response);
        assertEquals(List.of("error"), names(frames));
        assertTrue(json(frames.get(0)).path("message").asText().length() > 0, "错误事件必须可读");
    }

    @Test
    void toolFailureIsDataAndTheTurnStillEndsWithAnAnswer() throws Exception {
        // radius 99999 breaks SearchNearbyArgs' own bound: the registry turns it into a model-visible
        // error result, the loop hands it back, and the scripted follow-up turn answers.
        ScriptedLlm llm = new ScriptedLlm(
                Turn.tools(AgentTransportFixtures.call("c1", "search_nearby",
                        "{\"category\":\"火锅\",\"radius\":99999}")),
                Turn.answer("半径超出范围，我换个范围再试。"));
        wire(readOnlyFactory(llm));

        List<Frame> frames = stream("/agent/chat/stream", Map.of("message", "搜一下"));

        assertEquals(List.of("plan", "tool_call", "tool_result", "answer"), names(frames));
        assertTrue(json(frames.get(2)).path("isError").asBoolean(), "工具失败应作为 isError 结果返回");
        assertEquals("answer", names(frames).get(names(frames).size() - 1));
        assertEquals(0, catalog.searchNearbyCalls.get(), "参数非法时业务方法不得被调用");
    }

    @Test
    void resumeWithUnknownSessionEndsCleanlyWithAnErrorEvent() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        wire(readOnlyFactory(llm));

        List<Frame> frames = stream("/agent/chat/resume", Map.of("sessionId", "missing", "reply", "确认"));

        assertEquals(List.of("error"), names(frames));
        assertEquals(0, llm.streamCalls(), "未知会话不应调用模型");
    }

    // ------------------------------------------------------------------ 5. 凭据不泄漏

    @Test
    void aFailureMessageThatCarriesAKeyIsNeverEchoedOutward() throws Exception {
        // The realistic leak path is an adapter exception whose text embeds a key (an HTTP header or
        // URL). The transport must not echo it: the loop's ERROR result becomes a fixed readable
        // message and the logged line carries only the exception's class name.
        String key = "sk-live-do-not-leak-0123456789";
        ScriptedLlm llm = new ScriptedLlm(Turn.failing("调用失败：x-api-key=" + key));
        wire(readOnlyFactory(llm));

        List<Frame> frames = stream("/agent/chat/stream", Map.of("message", "hi"));

        assertEquals(List.of("error"), names(frames));
        for (Frame frame : frames) {
            assertFalse(frame.raw().contains(key), "SSE 帧不得含密钥");
            assertFalse(frame.raw().contains("x-api-key"), "SSE 帧不得回显 api-key 头内容");
        }
        assertFalse(frames.get(0).data().contains("LlmException"),
                "错误事件的正文是写死的可读文案，不携带异常类型以外的细节");
    }

    // ---------------------------------------------------------------- helpers

    private ToolRegistry registryWithBookingTool(AtomicInteger bookings) {
        return new ToolRegistry()
                .registerTools(new ShopTools(catalog))
                .register(ToolDefinition.of("book_shop", "预约到店（写操作，需确认）", Permission.WRITE,
                        ShopDetailArgs.class, args -> {
                            bookings.incrementAndGet();
                            return "booked";
                        }));
    }

    private static PermissionGate alwaysDefer(String question) {
        return (context, call, permission) -> PermissionDecision.defer(question);
    }

    /** Performs an SSE request and returns the frames once the async request has completed. */
    private List<Frame> stream(String path, Map<String, String> params) throws Exception {
        var builder = path.endsWith("resume") ? post(path) : get(path);
        params.forEach(builder::param);
        MvcResult result = mvc.perform(builder)
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(10_000);
        return parse(result.getResponse());
    }

    private static List<Frame> parse(MockHttpServletResponse response) {
        // Assert on the exact bytes the emitter wrote: the data is UTF-8 (the stream names it
        // explicitly), and the mock response's own character encoding is not what we are testing.
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        List<Frame> frames = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            String name = null;
            List<String> data = new ArrayList<>();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data.add(line.substring("data:".length()).trim());
                }
            }
            if (name != null) {
                frames.add(new Frame(name, String.join("\n", data)));
            }
        }
        return frames;
    }

    private static List<String> names(List<Frame> frames) {
        return frames.stream().map(Frame::name).toList();
    }

    private static long countToolCallsFor(List<Frame> frames, String callId) {
        return frames.stream().filter(f -> f.name().equals("tool_call"))
                .filter(f -> callId.equals(json(f).path("callId").asText())).count();
    }

    private static long countToolResultsFor(List<Frame> frames, String callId) {
        return frames.stream().filter(f -> f.name().equals("tool_result"))
                .filter(f -> callId.equals(json(f).path("callId").asText())).count();
    }

    private static JsonNode json(Frame frame) {
        try {
            return MAPPER.readTree(frame.data());
        } catch (Exception broken) {
            throw new IllegalStateException(broken);
        }
    }

    /** One parsed SSE frame. */
    private record Frame(String name, String data) {

        String raw() {
            return "event:" + name + "\ndata:" + data;
        }
    }
}
