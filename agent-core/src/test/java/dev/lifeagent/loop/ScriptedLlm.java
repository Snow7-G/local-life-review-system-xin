package dev.lifeagent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.lifeagent.llm.LlmClient;
import dev.lifeagent.llm.LlmException;
import dev.lifeagent.llm.LlmRequest;
import dev.lifeagent.llm.LlmStreamEvent;
import dev.lifeagent.llm.MessageDelta;
import dev.lifeagent.llm.StopReason;
import dev.lifeagent.llm.TextDelta;
import dev.lifeagent.llm.TokenUsage;
import dev.lifeagent.llm.ToolUseArgumentsDelta;
import dev.lifeagent.llm.ToolUseBlock;
import dev.lifeagent.llm.ToolUseEnd;
import dev.lifeagent.llm.ToolUseStart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A fake model: it replays a scripted list of turns and records every request it received.
 *
 * <p>This is the whole point of the loop depending on {@link LlmClient} rather than an adapter - the
 * tests can script a model that asks for tools, fails, or never gets called at all.</p>
 */
final class ScriptedLlm implements LlmClient {

    private final List<LlmTurn> script;
    private final AtomicInteger streamCalls = new AtomicInteger();
    private final List<LlmRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean failNext;
    private int index;

    ScriptedLlm(List<LlmTurn> script) {
        this.script = List.copyOf(script);
    }

    @Override
    public void stream(LlmRequest request, Consumer<? super LlmStreamEvent> events) {
        streamCalls.incrementAndGet();
        requests.add(request);
        if (failNext) {
            throw new LlmException("scripted transport failure");
        }
        LlmTurn turn = index < script.size() ? script.get(index++) : null;
        if (turn == null) {
            throw new LlmException("no more scripted turns");
        }
        if (!turn.text().isEmpty()) {
            events.accept(new TextDelta(turn.text()));
        }
        for (ToolUseBlock call : turn.toolCalls()) {
            events.accept(new ToolUseStart(call.id(), call.name()));
            events.accept(new ToolUseArgumentsDelta(call.id(), call.arguments().toString()));
            events.accept(new ToolUseEnd(call.id()));
        }
        events.accept(new MessageDelta(turn.stopReason(), turn.usage()));
    }

    int streamCalls() {
        return streamCalls.get();
    }

    List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    LlmRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }

    void failEveryCall() {
        failNext = true;
    }

    // ------------------------------------------------------------- builders

    static LlmTurn answer(String text) {
        return new LlmTurn(text, List.of(), StopReason.END_TURN, TokenUsage.ZERO);
    }

    static LlmTurn answer(String text, int tokens) {
        return new LlmTurn(text, List.of(), StopReason.END_TURN, new TokenUsage(0, tokens));
    }

    static LlmTurn wantsTools(ToolSpec... specs) {
        List<ToolUseBlock> calls = new ArrayList<>();
        for (ToolSpec spec : specs) {
            calls.add(new ToolUseBlock(spec.callId(), spec.toolName(), spec.arguments()));
        }
        return new LlmTurn("", calls, StopReason.TOOL_USE, TokenUsage.ZERO);
    }

    static ToolSpec call(String callId, String toolName, String json) {
        return new ToolSpec(callId, toolName, json(json));
    }

    static JsonNode json(String raw) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** One scripted tool call. */
    record ToolSpec(String callId, String toolName, JsonNode arguments) {
    }

    static JsonNode empty() {
        return JsonNodeFactory.instance.objectNode();
    }
}
