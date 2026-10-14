package com.hmdp.agent.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import dev.lifeagent.llm.ToolUseEnd;
import dev.lifeagent.llm.ToolUseStart;
import dev.lifeagent.tool.ShopBrief;
import dev.lifeagent.tool.ShopCatalog;
import dev.lifeagent.tool.ShopDetail;
import dev.lifeagent.tool.ShopDetailArgs;
import dev.lifeagent.tool.SearchNearbyArgs;
import dev.lifeagent.tool.VoucherBrief;
import dev.lifeagent.tool.VoucherListArgs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Fakes the transport tests drive the loop with; none of them touches the network. */
final class AgentTransportFixtures {

    private AgentTransportFixtures() {
    }

    // --------------------------------------------------------------- shop fakes

    /**
     * A read-only catalog whose two tools let the loop emit plan/tool_call/tool_result without any
     * Spring service. Every invocation is counted so "the finished call is not re-run" is provable.
     */
    static final class CountingCatalog implements ShopCatalog {

        final AtomicInteger searchNearbyCalls = new AtomicInteger();
        final AtomicInteger shopDetailCalls = new AtomicInteger();
        final List<String> searchedCategories = Collections.synchronizedList(new ArrayList<>());

        @Override
        public List<ShopBrief> searchNearby(SearchNearbyArgs args) {
            searchNearbyCalls.incrementAndGet();
            searchedCategories.add(args.category());
            return List.of(new ShopBrief("1", "顺时针火锅", args.category(), 88, 4.6));
        }

        @Override
        public ShopDetail shopDetail(ShopDetailArgs args) {
            shopDetailCalls.incrementAndGet();
            return new ShopDetail(String.valueOf(args.shopId()), "顺时针火锅", "美食",
                    "文一西路 1 号", 88, 4.6);
        }

        @Override
        public List<VoucherBrief> voucherList(VoucherListArgs args) {
            return List.of();
        }
    }

    // -------------------------------------------------------------- scripted llm

    /** One scripted tool call. */
    record Call(String id, String name, String json) {
    }

    /** One scripted model response. */
    record Turn(String text, List<Call> calls, StopReason reason, String failure) {

        static Turn answer(String text) {
            return new Turn(text, List.of(), StopReason.END_TURN, null);
        }

        static Turn tools(Call... calls) {
            return new Turn("", List.of(calls), StopReason.TOOL_USE, null);
        }

        static Turn failing() {
            return failing("scripted transport failure");
        }

        static Turn failing(String message) {
            return new Turn("", List.of(), StopReason.END_TURN, message);
        }
    }

    static Call call(String id, String name, String json) {
        return new Call(id, name, json);
    }

    /**
     * A fake model that replays turns and records how many times it was called.
     *
     * <p>Hand-rolled rather than shared with agent-core's own {@code ScriptedLlm}: that class is
     * package-private to {@code dev.lifeagent.loop} and must not be changed, so the transport tests
     * carry their own copy of the idea.</p>
     */
    static final class ScriptedLlm implements LlmClient {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final List<Turn> script;
        private final AtomicInteger streamCalls = new AtomicInteger();
        private int index;

        ScriptedLlm(Turn... turns) {
            this.script = List.of(turns);
        }

        @Override
        public void stream(LlmRequest request, Consumer<? super LlmStreamEvent> events) {
            streamCalls.incrementAndGet();
            Turn turn = index < script.size() ? script.get(index++) : null;
            if (turn == null) {
                throw new LlmException("no more scripted turns");
            }
            if (turn.failure() != null) {
                throw new LlmException(turn.failure());
            }
            if (!turn.text().isEmpty()) {
                events.accept(new TextDelta(turn.text()));
            }
            for (Call call : turn.calls()) {
                events.accept(new ToolUseStart(call.id(), call.name()));
                events.accept(new ToolUseArgumentsDelta(call.id(), call.json()));
                events.accept(new ToolUseEnd(call.id()));
            }
            events.accept(new MessageDelta(turn.reason(), TokenUsage.ZERO));
        }

        int streamCalls() {
            return streamCalls.get();
        }
    }

    static JsonNode json(String raw) {
        try {
            return new ObjectMapper().readTree(raw);
        } catch (Exception broken) {
            throw new IllegalArgumentException(broken);
        }
    }

    static JsonNode emptyObject() {
        return JsonNodeFactory.instance.objectNode();
    }
}
