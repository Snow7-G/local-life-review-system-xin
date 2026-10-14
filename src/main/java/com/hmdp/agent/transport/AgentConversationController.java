package com.hmdp.agent.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lifeagent.event.ConfirmRequiredEvent;
import dev.lifeagent.loop.AgentLoop;
import dev.lifeagent.loop.LoopConfig;
import dev.lifeagent.loop.LoopResult;
import dev.lifeagent.loop.LoopSession;
import dev.lifeagent.loop.LoopStopReason;
import dev.lifeagent.loop.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Optional;

/**
 * The SSE conversation endpoint: forwards the six {@code AgentEvent}s the planner loop produces and
 * lets a paused turn be answered and resumed.
 *
 * <p><strong>Read-only by construction.</strong> Both routes only drive the read-only planner loop
 * ({@code search_nearby}/{@code shop_detail}/{@code voucher_list}); they change no business state,
 * register no management route and add no write interface. Conversation state lives in the injected
 * {@link SessionStore}, never in the {@code hm-dp} database.</p>
 *
 * <p><strong>The transport owns the sixth event.</strong> agent-core constructs only four of the six
 * events (plan/tool_call/tool_result/answer). This controller turns "the loop stopped with
 * {@link LoopStopReason#WAITING_USER}" into {@code confirm_required} - details taken from the stored
 * session - and turns an unrepresentable failure into a readable {@code error} frame.</p>
 *
 * <p><strong>Non-blocking pause.</strong> When a turn stops with {@code WAITING_USER} the loop has
 * already saved the session, so the controller pushes {@code confirm_required} and completes the
 * emitter immediately: no thread stays parked on the request, and {@code POST /agent/chat/resume}
 * starts a fresh request that loads the session back.</p>
 */
@RestController
public class AgentConversationController {

    private static final Logger log = LoggerFactory.getLogger(AgentConversationController.class);

    /** Session id used when the client does not supply one. */
    public static final String DEFAULT_SESSION_ID = "default";

    private final AgentLoopFactory loopFactory;
    private final SessionStore sessionStore;
    private final AgentEventStream stream;
    private final ObjectMapper mapper;
    private final AgentTransportSettings settings;

    /**
     * @param loopFactory  builds a loop wired to this request's event sink
     * @param sessionStore where suspended sessions are stored, keyed by session id
     * @param stream       the six-event -&gt; SSE frame mapper
     * @param mapper       used only to validate the optional {@code origin} argument
     * @param settings     non-secret defaults for model and system prompt
     */
    public AgentConversationController(
            AgentLoopFactory loopFactory,
            SessionStore sessionStore,
            AgentEventStream stream,
            ObjectMapper mapper,
            AgentTransportSettings settings) {
        this.loopFactory = loopFactory;
        this.sessionStore = sessionStore;
        this.stream = stream;
        this.mapper = mapper;
        this.settings = settings;
    }

    /**
     * Runs one turn and streams its events.
     *
     * @param message      the user's message for this turn
     * @param sessionId    the session to continue, or {@code null} for the default session
     * @param systemPrompt optional system prompt
     * @param model        optional provider model id
     * @param origin       optional {@code {"x":number,"y":number}} position hint, validated and unused
     * @return the stream of {@code plan/tool_call/tool_result/thinking/confirm_required/answer}
     */
    @GetMapping(value = "/agent/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
            @RequestParam("message") String message,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "systemPrompt", required = false) String systemPrompt,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "origin", required = false) String origin) {
        SseEmitter emitter = new SseEmitter();
        if (message == null || message.isBlank()) {
            stream.sendError(emitter, "message 不能为空。");
            return complete(emitter);
        }
        if (!validOrigin(origin)) {
            stream.sendError(emitter, "origin 必须是 {\"x\":数字,\"y\":数字}。");
            return complete(emitter);
        }
        LoopConfig config = LoopConfig
                .of(sessionId(sessionId), settings.systemPromptOr(systemPrompt), message)
                .withModel(settings.modelOr(model));
        drive(emitter, loop -> loop.run(config));
        return emitter;
    }

    /**
     * Resumes a turn that stopped with {@code confirm_required}.
     *
     * @param sessionId the suspended session id
     * @param reply     the user's answer
     * @return the remainder of the stream, ending with {@code answer}
     */
    @PostMapping(value = "/agent/chat/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("reply") String reply) {
        SseEmitter emitter = new SseEmitter();
        if (sessionId == null || sessionId.isBlank()) {
            stream.sendError(emitter, "sessionId 不能为空。");
            return complete(emitter);
        }
        if (reply == null) {
            stream.sendError(emitter, "reply 不能为空。");
            return complete(emitter);
        }
        Optional<LoopSession> stored = sessionStore.load(sessionId);
        if (stored.isEmpty()) {
            stream.sendError(emitter, "会话不存在或已过期：" + sessionId);
            return complete(emitter);
        }
        LoopSession session = stored.get();
        if (!session.isWaitingForUser()) {
            stream.sendError(emitter, "该会话当前不需要用户确认。");
            return complete(emitter);
        }
        drive(emitter, loop -> loop.resume(session, reply));
        return emitter;
    }

    private void drive(SseEmitter emitter, AgentLoopFactory.Step step) {
        try {
            AgentLoop loop = loopFactory.create(stream.sink(emitter));
            LoopResult result = step.run(loop);
            switch (result.stopReason()) {
                case WAITING_USER -> pushConfirmRequired(emitter, result.sessionId());
                case ERROR -> {
                    // The loop turns a model failure into ERROR data and emits no answer, so the
                    // transport closes the stream with a readable frame. The message is fixed on
                    // purpose: an adapter's exception text is never echoed outward.
                    stream.sendError(emitter, "模型调用失败，请稍后重试。");
                }
                default -> {
                }
            }
        } catch (RuntimeException failure) {
            // A readable frame instead of an HTML 500; the message is fixed, never the exception text.
            log.warn("agent 对话流处理失败: {}", failure.getClass().getSimpleName());
            stream.sendError(emitter, "服务处理本次请求时出错，请稍后重试。");
        } finally {
            complete(emitter);
        }
    }

    /**
     * Pushes the {@code confirm_required} frame for a paused turn, reading the pending call back from
     * the store the loop just wrote to.
     */
    private void pushConfirmRequired(SseEmitter emitter, String sessionId) {
        sessionStore.load(sessionId)
                .map(LoopSession::pendingPermission)
                .ifPresent(pending -> stream.send(emitter,
                        new ConfirmRequiredEvent(pending.request().callId(),
                                pending.request().toolName(), pending.question())));
    }

    private boolean validOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            JsonNode node = mapper.readTree(origin);
            return node.isObject() && node.path("x").isNumber() && node.path("y").isNumber();
        } catch (Exception malformed) {
            return false;
        }
    }

    private static String sessionId(String requested) {
        return requested == null || requested.isBlank() ? DEFAULT_SESSION_ID : requested;
    }

    private static SseEmitter complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // Already completed or the connection is gone.
        }
        return emitter;
    }
}
