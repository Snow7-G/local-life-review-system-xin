package dev.lifeagent.loop;

import dev.lifeagent.event.AgentEvent;
import dev.lifeagent.hook.HookChain;
import dev.lifeagent.tool.AgentTool;
import dev.lifeagent.tool.Permission;
import dev.lifeagent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared fixture: a probe tool provider, a recording event sink, and a loop builder.
 *
 * <p>The two read probes are wired so that {@code probe_read_a} cannot finish until
 * {@code probe_read_b} has: if they ran one after the other, the first would time out. That single
 * latch therefore proves both "they ran at the same time" and "the second finished first".</p>
 */
public final class LoopHarness {

    static final long PROBE_WAIT_MILLIS = 5_000;

    private LoopHarness() {
    }

    /** Tools the tests can script. */
    public static final class ProbeTools {

        final CountDownLatch readsStarted = new CountDownLatch(2);
        final CountDownLatch readBFinished = new CountDownLatch(1);
        final AtomicBoolean readAOverlapped = new AtomicBoolean();
        final AtomicInteger readCalls = new AtomicInteger();
        final AtomicInteger writeCalls = new AtomicInteger();
        final AtomicInteger failingCalls = new AtomicInteger();
        final AtomicInteger unknownCalled = new AtomicInteger();
        final AtomicInteger writesInFlight = new AtomicInteger();
        final AtomicInteger maxWritesInFlight = new AtomicInteger();
        final AtomicInteger writeFailCalls = new AtomicInteger();
        final AtomicInteger flakyWriteCalls = new AtomicInteger();
        volatile int flakyWriteFailuresAlreadyServed;
        /** Extra call numbers (1-based) that must fail, so a success can be placed between failures. */
        final Set<Integer> flakyWriteFailOn = ConcurrentHashMap.newKeySet();
        final AtomicInteger errorCalls = new AtomicInteger();
        final AtomicInteger writeErrorCalls = new AtomicInteger();
        final AtomicInteger pingCalls = new AtomicInteger();
        volatile int flakyFailuresAlreadyServed;
        final AtomicInteger flakyCalls = new AtomicInteger();
        final List<String> writeOrder = Collections.synchronizedList(new ArrayList<>());
        final List<String> finishedOrder = Collections.synchronizedList(new ArrayList<>());
        final List<String> concurrentlyRunning = Collections.synchronizedList(new ArrayList<>());

        @AgentTool(name = "probe_read_a", permission = Permission.READ, description = "只读探针 A")
        public String readA(ProbeArgs args) {
            return readAInternal();
        }

        @AgentTool(name = "probe_read_b", permission = Permission.READ, description = "只读探针 B")
        public String readB(ProbeArgs args) {
            readCalls.incrementAndGet();
            concurrentlyRunning.add("probe_read_b");
            readsStarted.countDown();
            // Record the finish BEFORE releasing readA: otherwise readA can wake from the latch and
            // append its own entry first, making the completion order a race. The overlap this proves
            // is unchanged — readB has finished while readA is still blocked.
            finishedOrder.add("probe_read_b");
            readBFinished.countDown();
            return "probe_read_b:ok";
        }

        @AgentTool(name = "probe_write", permission = Permission.WRITE, description = "写探针")
        public String write(ProbeArgs args) {
            int inFlight = writesInFlight.incrementAndGet();
            maxWritesInFlight.accumulateAndGet(inFlight, Math::max);
            try {
                writeCalls.incrementAndGet();
                writeOrder.add(args.value());
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return "wrote:" + args.value();
            } finally {
                writesInFlight.decrementAndGet();
            }
        }

        @AgentTool(name = "probe_flaky", permission = Permission.READ, description = "先失败后成功的探针")
        public String flaky(ProbeArgs args) {
            int call = flakyCalls.incrementAndGet();
            if (call <= flakyFailuresAlreadyServed) {
                throw new IllegalStateException("flaky failure " + call);
            }
            return "flaky:ok#" + call;
        }

        @AgentTool(name = "probe_fail", permission = Permission.READ, description = "必败探针")
        public String fail(ProbeArgs args) {
            failingCalls.incrementAndGet();
            throw new IllegalStateException("probe failure " + failingCalls.get());
        }

        @AgentTool(name = "probe_high_risk", permission = Permission.HIGH_RISK, description = "高危探针")
        public String highRisk(ProbeArgs args) {
            return "high-risk:" + args.value();
        }

        @AgentTool(name = "probe_write_fail", permission = Permission.WRITE, description = "必败写探针")
        public String writeFail(ProbeArgs args) {
            writeFailCalls.incrementAndGet();
            throw new IllegalStateException("write failure " + writeFailCalls.get());
        }

        @AgentTool(name = "probe_flaky_write", permission = Permission.WRITE, description = "先失败后成功的写探针")
        public String flakyWrite(ProbeArgs args) {
            int call = flakyWriteCalls.incrementAndGet();
            if (call <= flakyWriteFailuresAlreadyServed || flakyWriteFailOn.contains(call)) {
                throw new IllegalStateException("flaky write failure " + call);
            }
            return "flaky_write:ok#" + call;
        }

        @AgentTool(name = "probe_write_error", permission = Permission.WRITE, description = "抛 Error 的写探针")
        public String writeError(ProbeArgs args) {
            writeErrorCalls.incrementAndGet();
            throw new AssertionError("probe write boom");
        }

        @AgentTool(name = "probe_read_error", permission = Permission.READ, description = "抛 Error 的只读探针")
        public String readError(ProbeArgs args) {
            errorCalls.incrementAndGet();
            throw new AssertionError("probe boom");
        }

        @AgentTool(name = "probe_ping", permission = Permission.READ, description = "必成功只读探针")
        public String ping(ProbeArgs args) {
            return "pong:" + pingCalls.incrementAndGet();
        }

        private String readAInternal() {
            readCalls.incrementAndGet();
            concurrentlyRunning.add("probe_read_a");
            readsStarted.countDown();
            boolean overlapped;
            try {
                overlapped = readBFinished.await(PROBE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                overlapped = false;
            }
            readAOverlapped.set(overlapped);
            if (!overlapped) {
                throw new IllegalStateException("probe_read_b never overlapped probe_read_a");
            }
            finishedOrder.add("probe_read_a");
            return "probe_read_a:ok";
        }
    }

    /** Argument record for the probe tools. */
    public record ProbeArgs(String value) {
    }

    static ToolRegistry registry(ProbeTools provider) {
        return new ToolRegistry().registerTools(provider);
    }

    /** Collects every event the loop pushes, in order. */
    public static final class RecordingSink {

        private final List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());

        void accept(AgentEvent event) {
            events.add(event);
        }

        List<AgentEvent> events() {
            return List.copyOf(events);
        }

        List<AgentEvent> ofType(Class<? extends AgentEvent> type) {
            return events().stream().filter(type::isInstance).toList();
        }
    }

    static AgentLoop loop(ScriptedLlm llm, ToolRegistry registry, PermissionGate gate) {
        return new AgentLoop(llm, registry, HookChain.empty(), gate);
    }

    static AgentLoop loop(
            ScriptedLlm llm,
            ToolRegistry registry,
            HookChain hooks,
            PermissionGate gate,
            RecordingSink sink) {
        return new AgentLoop(llm, registry, hooks, gate, new InMemorySessionStore(),
                new ToolCircuitBreaker(3), sink::accept);
    }

    static AgentLoop loop(
            ScriptedLlm llm,
            ToolRegistry registry,
            PermissionGate gate,
            SessionStore store,
            RecordingSink sink) {
        return new AgentLoop(llm, registry, HookChain.empty(), gate, store,
                new ToolCircuitBreaker(3), sink::accept);
    }

    static LoopConfig config(String sessionId, String userMessage, LoopBudget budget) {
        return new LoopConfig(sessionId, "system", null, userMessage, budget);
    }

    static ScriptedLlm.ToolSpec readA(String callId) {
        return ScriptedLlm.call(callId, "probe_read_a", "{\"value\":\"a\"}");
    }

    static ScriptedLlm.ToolSpec readB(String callId) {
        return ScriptedLlm.call(callId, "probe_read_b", "{\"value\":\"b\"}");
    }

    static ScriptedLlm.ToolSpec write(String callId, String value) {
        return ScriptedLlm.call(callId, "probe_write", "{\"value\":\"" + value + "\"}");
    }

    static ScriptedLlm.ToolSpec fail(String callId) {
        return ScriptedLlm.call(callId, "probe_fail", "{\"value\":\"x\"}");
    }

    static ScriptedLlm.ToolSpec writeError(String callId) {
        return ScriptedLlm.call(callId, "probe_write_error", "{\"value\":\"x\"}");
    }

    static ScriptedLlm.ToolSpec flaky(String callId) {
        return ScriptedLlm.call(callId, "probe_flaky", "{\"value\":\"x\"}");
    }

    static ScriptedLlm.ToolSpec writeFail(String callId) {
        return ScriptedLlm.call(callId, "probe_write_fail", "{\"value\":\"x\"}");
    }

    static ScriptedLlm.ToolSpec flakyWrite(String callId) {
        return ScriptedLlm.call(callId, "probe_flaky_write", "{\"value\":\"x\"}");
    }

    static ScriptedLlm.ToolSpec readError(String callId) {
        return ScriptedLlm.call(callId, "probe_read_error", "{\"value\":\"x\"}");
    }

    static ScriptedLlm.ToolSpec ping(String callId) {
        return ScriptedLlm.call(callId, "probe_ping", "{\"value\":\"x\"}");
    }

}
