package com.hmdp.agent.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.lifeagent.event.AgentEvent;
import dev.lifeagent.event.AnswerEvent;
import dev.lifeagent.event.ConfirmRequiredEvent;
import dev.lifeagent.event.PlanEvent;
import dev.lifeagent.event.ThinkingEvent;
import dev.lifeagent.event.ToolCallEvent;
import dev.lifeagent.event.ToolResultEvent;

import java.util.Objects;
import java.util.function.Consumer;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Turns agent-core's six {@link AgentEvent} types into {@code text/event-stream} frames.
 *
 * <p>This is the only place that knows the wire names. The mapping is one-to-one and exhaustive -
 * the {@link AgentEvent} interface is sealed with exactly six members, so a new member would fail
 * compilation right here instead of silently disappearing from the stream.</p>
 *
 * <table>
 *   <caption>Event name mapping</caption>
 *   <tr><th>Event type</th><th>SSE {@code event:}</th><th>data fields</th></tr>
 *   <tr><td>{@link PlanEvent}</td><td>{@code plan}</td><td>{@code summary}, {@code steps[]}</td></tr>
 *   <tr><td>{@link ToolCallEvent}</td><td>{@code tool_call}</td><td>{@code callId}, {@code toolName}, {@code arguments}</td></tr>
 *   <tr><td>{@link ToolResultEvent}</td><td>{@code tool_result}</td><td>{@code callId}, {@code toolName}, {@code isError}, {@code content}</td></tr>
 *   <tr><td>{@link ThinkingEvent}</td><td>{@code thinking}</td><td>{@code text}</td></tr>
 *   <tr><td>{@link ConfirmRequiredEvent}</td><td>{@code confirm_required}</td><td>{@code callId}, {@code toolName}, {@code reason}</td></tr>
 *   <tr><td>{@link AnswerEvent}</td><td>{@code answer}</td><td>{@code text}</td></tr>
 * </table>
 *
 * <p>An {@code error} frame is also produced, but only for a failure the loop could not turn into
 * data (see {@link #sink(SseEmitter)}). It is a transport-level terminator, not one of the six
 * conversation events.</p>
 */
public final class AgentEventStream {

    /** Sent explicitly so the payload is always UTF-8, regardless of the container's default. */
    private static final MediaType UTF8_TEXT = new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8);

    private final ObjectMapper mapper;

    /**
     * @param mapper the Jackson mapper used for every {@code data:} payload
     */
    public AgentEventStream(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * @param emitter the stream to write to
     * @return the event sink to hand to {@code AgentLoop}'s constructor
     */
    public Consumer<AgentEvent> sink(SseEmitter emitter) {
        Objects.requireNonNull(emitter, "emitter");
        return event -> send(emitter, event);
    }

    /**
     * Writes one event as an SSE frame named after its type.
     *
     * @param emitter the stream to write to
     * @param event   the event to forward
     */
    public void send(SseEmitter emitter, AgentEvent event) {
        Objects.requireNonNull(emitter, "emitter");
        Objects.requireNonNull(event, "event");
        try {
            String json = mapper.writeValueAsString(payload(event));
            emitter.send(SseEmitter.event().name(name(event)).data(json, UTF8_TEXT));
        } catch (JsonProcessingException problem) {
            sendFailure(emitter, problem);
        } catch (java.io.IOException closed) {
            // The client went away; there is nothing to write to any more.
        }
    }

    /**
     * @param event any of the six event types
     * @return the SSE {@code event:} name for it
     */
    public static String name(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        return switch (event) {
            case PlanEvent ignored -> "plan";
            case ToolCallEvent ignored -> "tool_call";
            case ToolResultEvent ignored -> "tool_result";
            case ThinkingEvent ignored -> "thinking";
            case ConfirmRequiredEvent ignored -> "confirm_required";
            case AnswerEvent ignored -> "answer";
        };
    }

    /**
     * @param event any of the six event types
     * @return the JSON object put in the frame's {@code data:} line
     */
    public JsonNode payload(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        ObjectNode node = mapper.createObjectNode();
        switch (event) {
            case PlanEvent plan -> {
                node.put("summary", plan.summary());
                ArrayNode steps = node.putArray("steps");
                plan.steps().forEach(steps::add);
            }
            case ToolCallEvent call -> {
                node.put("callId", call.callId());
                node.put("toolName", call.toolName());
                node.set("arguments", call.arguments());
            }
            case ToolResultEvent result -> {
                node.put("callId", result.callId());
                node.put("toolName", result.toolName());
                node.put("isError", result.isError());
                node.set("content", result.content());
            }
            case ThinkingEvent thinking -> node.put("text", thinking.text());
            case ConfirmRequiredEvent confirm -> {
                node.put("callId", confirm.callId());
                node.put("toolName", confirm.toolName());
                node.put("reason", confirm.reason());
            }
            case AnswerEvent answer -> node.put("text", answer.text());
        }
        return node;
    }

    /**
     * Writes a readable {@code error} frame and completes the stream.
     *
     * @param emitter the stream to write to
     * @param message a user-facing message; must never carry credentials
     */
    public void sendError(SseEmitter emitter, String message) {
        sendFailure(emitter, null, message);
    }

    private void sendFailure(SseEmitter emitter, JsonProcessingException problem) {
        sendFailure(emitter, problem, "事件序列化失败，请重试。");
    }

    private void sendFailure(SseEmitter emitter, JsonProcessingException problem, String message) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("message", message);
            if (problem != null) {
                node.put("detail", problem.getClass().getSimpleName());
            }
            emitter.send(SseEmitter.event().name("error").data(mapper.writeValueAsString(node), UTF8_TEXT));
        } catch (java.io.IOException ignored) {
            // Best effort: the connection is already broken.
        }
    }

}
