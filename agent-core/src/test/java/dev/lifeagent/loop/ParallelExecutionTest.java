package dev.lifeagent.loop;

import dev.lifeagent.event.ToolCallEvent;
import dev.lifeagent.event.ToolResultEvent;
import dev.lifeagent.hook.HookChain;
import dev.lifeagent.loop.LoopHarness.ProbeTools;
import dev.lifeagent.loop.LoopHarness.RecordingSink;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirement (b): read-only tool calls really run at the same time, write calls run one at a time,
 * and results are always filled back in the order the model asked for them.
 */
class ParallelExecutionTest {

    private static final PermissionGate ALLOW_ALL = (context, call, permission) -> PermissionDecision.allow();

    /** Proof (i): two read-only tools must genuinely be in flight together. */
    @Test
    void readOnlyToolsRunConcurrentlyOnVirtualThreads() {
        ProbeTools probe = new ProbeTools();
        ScriptedLlm llm = new ScriptedLlm(List.of(
                ScriptedLlm.wantsTools(LoopHarness.readA("call-a"), LoopHarness.readB("call-b")),
                ScriptedLlm.answer("两段结果都拿到了")));

        LoopResult result = LoopHarness.loop(llm, LoopHarness.registry(probe), ALLOW_ALL)
                .run(LoopHarness.config("s-parallel", "找店", LoopBudget.defaults()));

        assertEquals(LoopStopReason.ANSWERED, result.stopReason());
        // probe_read_a would have timed out (and thrown) if probe_read_b had not been running at
        // the same time, so both of these are concurrency proof, not decoration.
        assertTrue(probe.readAOverlapped.get(), "probe_read_a must have overlapped probe_read_b");
        assertEquals(2, probe.readCalls.get(), "both read tools must have run exactly once");
        assertEquals(List.of("probe_read_b", "probe_read_a"), probe.finishedOrder,
                "probe_read_b finishes first: the calls are concurrent, not serial");
    }

    /** Requirement (b): the history is filled back in request order even though b finished first. */
    @Test
    void resultsAreFilledBackInRequestOrderDespiteOutOfOrderCompletion() {
        ProbeTools probe = new ProbeTools();
        RecordingSink sink = new RecordingSink();
        ScriptedLlm llm = new ScriptedLlm(List.of(
                ScriptedLlm.wantsTools(LoopHarness.readA("call-a"), LoopHarness.readB("call-b")),
                ScriptedLlm.answer("done")));

        LoopResult result = LoopHarness.loop(llm, LoopHarness.registry(probe), HookChain.empty(),
                ALLOW_ALL, sink).run(LoopHarness.config("s-order", "找店", LoopBudget.defaults()));

        assertEquals(LoopStopReason.ANSWERED, result.stopReason());

        List<ToolResultEvent> toolResults = sink.ofType(ToolResultEvent.class).stream()
                .map(event -> (ToolResultEvent) event)
                .toList();
        assertEquals(List.of("call-a", "call-b"), toolResults.stream()
                .map(ToolResultEvent::callId).toList(),
                "results must be reported in the order the model requested them");

        // The model received the same order: the tool results follow the request order.
        assertEquals(List.of("call-a", "call-b"), toolResultIds(llm.lastRequest().messages()));
    }

    /** Requirement (b): mixed steps parallelise the reads and serialise the writes. */
    @Test
    void mixedStepRunsReadsConcurrentlyAndWritesSerially() {
        ProbeTools probe = new ProbeTools();
        RecordingSink sink = new RecordingSink();
        ScriptedLlm llm = new ScriptedLlm(List.of(
                ScriptedLlm.wantsTools(LoopHarness.readA("call-a"), LoopHarness.readB("call-b"),
                        LoopHarness.write("call-w1", "one"), LoopHarness.write("call-w2", "two")),
                ScriptedLlm.answer("done")));

        LoopResult result = LoopHarness.loop(llm, LoopHarness.registry(probe), HookChain.empty(),
                ALLOW_ALL, sink).run(LoopHarness.config("s-mixed", "下单", LoopBudget.defaults()));

        assertEquals(LoopStopReason.ANSWERED, result.stopReason());
        assertTrue(probe.readAOverlapped.get(), "the read pair must still overlap in a mixed step");
        assertEquals(1, probe.maxWritesInFlight.get(), "write tools must never run concurrently");
        assertEquals(List.of("one", "two"), probe.writeOrder, "writes must keep declaration order");

        assertEquals(List.of("call-a", "call-b", "call-w1", "call-w2"),
                sink.ofType(ToolResultEvent.class).stream()
                        .map(event -> ((ToolResultEvent) event).callId()).toList(),
                "mixed results must still come back in request order");

        List<ToolCallEvent> calls = sink.ofType(ToolCallEvent.class).stream()
                .map(event -> (ToolCallEvent) event).toList();
        assertEquals(List.of("call-a", "call-b", "call-w1", "call-w2"),
                calls.stream().map(ToolCallEvent::callId).toList());
    }

    /**
     * Requirement (b), the other half of the contract: a non-{@code READ} call is a barrier between
     * read runs. The model asked for readA, then a write, then readB, so readA and readB are in
     * <em>different</em> runs and must not be assumed to overlap — this is what keeps a suspended
     * write from letting a later read run (and then re-run on resume). The read still has to come
     * back as a normal (here: failed) result, never as an exception out of the loop.
     */
    @Test
    void aWriteSplitsReadRunsSoInterleavedReadsNeedNotOverlap() {
        ProbeTools probe = new ProbeTools();
        RecordingSink sink = new RecordingSink();
        ScriptedLlm llm = new ScriptedLlm(List.of(
                ScriptedLlm.wantsTools(LoopHarness.readA("call-a"), LoopHarness.write("call-w1", "one"),
                        LoopHarness.readB("call-b")),
                ScriptedLlm.answer("done")));

        LoopResult result = LoopHarness.loop(llm, LoopHarness.registry(probe), HookChain.empty(),
                ALLOW_ALL, sink).run(LoopHarness.config("s-barrier", "下单", LoopBudget.defaults()));

        assertEquals(LoopStopReason.ANSWERED, result.stopReason(), "a failing tool must not end the loop");
        assertFalse(probe.readAOverlapped.get(),
                "reads separated by a write belong to different runs and must not be forced to overlap");

        List<ToolResultEvent> results = sink.ofType(ToolResultEvent.class).stream()
                .map(event -> (ToolResultEvent) event).toList();
        assertEquals(List.of("call-a", "call-w1", "call-b"),
                results.stream().map(ToolResultEvent::callId).toList(),
                "every call still gets its result slot, in request order");
    }

    /** Requirement (d): a denied write never touches the tool, but still gets a result slot. */
    @Test
    void deniedWriteIsNotExecutedAndBecomesAnErrorResult() {
        ProbeTools probe = new ProbeTools();
        ScriptedLlm llm = new ScriptedLlm(List.of(
                ScriptedLlm.wantsTools(LoopHarness.write("call-w", "one")),
                ScriptedLlm.answer("换条路")));

        LoopResult result = LoopHarness.loop(llm, LoopHarness.registry(probe),
                        PermissionGate.denyAll())
                .run(LoopHarness.config("s-denied", "下单", LoopBudget.defaults()));

        assertEquals(LoopStopReason.ANSWERED, result.stopReason());
        assertEquals(0, probe.writeCalls.get(), "a denied tool must not be entered");

        assertEquals(List.of("call-w"), toolResultIds(llm.lastRequest().messages()));
        var block = lastToolResult(llm);
        assertTrue(block.isError(), "a denial must reach the model as an error result");
        assertTrue(block.content().asText().contains("W5"), "the reason must name the missing flow");
    }

    /** Hard boundary: the permission gate is only consulted for non-READ tools. */
    @Test
    void readToolsNeverConsultThePermissionGate() {
        ProbeTools probe = new ProbeTools();
        var gateCalls = new java.util.concurrent.atomic.AtomicInteger();
        PermissionGate counting = (context, call, permission) -> {
            gateCalls.incrementAndGet();
            return PermissionDecision.allow();
        };
        ScriptedLlm llm = new ScriptedLlm(List.of(
                ScriptedLlm.wantsTools(LoopHarness.readA("call-a"), LoopHarness.readB("call-b")),
                ScriptedLlm.answer("done")));

        LoopHarness.loop(llm, LoopHarness.registry(probe), counting)
                .run(LoopHarness.config("s-read-gate", "找店", LoopBudget.defaults()));

        assertEquals(0, gateCalls.get(), "READ tools are let through without asking the gate");
        assertEquals(2, probe.readCalls.get());
    }

    private static List<String> toolResultIds(List<dev.lifeagent.llm.Message> messages) {
        return messages.stream()
                .filter(message -> message.role() == dev.lifeagent.llm.Role.TOOL)
                .flatMap(message -> message.content().stream())
                .map(block -> ((dev.lifeagent.llm.ToolResultBlock) block).toolUseId())
                .toList();
    }

    private static dev.lifeagent.llm.ToolResultBlock lastToolResult(ScriptedLlm llm) {
        List<dev.lifeagent.llm.Message> messages = llm.lastRequest().messages();
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index).role() == dev.lifeagent.llm.Role.TOOL) {
                return (dev.lifeagent.llm.ToolResultBlock) messages.get(index).content().getFirst();
            }
        }
        throw new AssertionError("no tool result was sent to the model");
    }
}
