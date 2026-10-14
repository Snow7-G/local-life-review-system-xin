package com.hmdp.agent.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lifeagent.llm.LlmClient;
import dev.lifeagent.llm.LlmException;
import dev.lifeagent.llm.LlmRequest;
import dev.lifeagent.llm.LlmStreamEvent;
import dev.lifeagent.llm.MessageDelta;
import dev.lifeagent.llm.Role;
import dev.lifeagent.llm.StopReason;
import dev.lifeagent.llm.TextDelta;
import dev.lifeagent.llm.TokenUsage;
import dev.lifeagent.llm.ToolResultBlock;
import dev.lifeagent.llm.ToolUseArgumentsDelta;
import dev.lifeagent.llm.ToolUseEnd;
import dev.lifeagent.llm.ToolUseStart;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Fixtures for the service-level end-to-end tests (W2-6).
 *
 * <p>Nothing here touches Spring, MySQL, Redis or a socket. The two fakes below are deliberately
 * hand-written {@link Proxy} stubs instead of Mockito mocks: Mockito's inline mock maker
 * self-attaches through a socket, which this sandbox forbids.</p>
 *
 * <p>{@link ScriptedLlm} is a copy of the transport suite's idea rather than a reuse of
 * {@code AgentTransportFixtures.ScriptedLlm}: that class is package-private to
 * {@code com.hmdp.agent.transport} and the task card forbids changing existing tests, so a shared
 * type is impossible without editing them. The one addition here is {@link #requests()}: the E2E
 * test asserts on the tool result the model <em>actually received</em>, which the transport copy
 * does not record.</p>
 */
final class E2eFixtures {

    private E2eFixtures() {
    }

    // ------------------------------------------------------------ fake services

    /**
     * A recording stand-in for one application service.
     *
     * <p>Unlike {@code SpringShopCatalogTest.Fake}, an unstubbed method does <em>not</em> fail with an
     * {@link AssertionError}. That is what lets the read-only assertion see a write that was actually
     * called and report it, instead of the write throwing before the read path has finished. Reads
     * that the adapter is expected to make are stubbed with {@link #on}; an unstubbed call yields
     * {@code null} and is still recorded in {@link #calls()}.</p>
     *
     * @param <T> the service interface being faked
     */
    static final class RecordingService<T> {

        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, List<Object[]>> arguments = new HashMap<>();
        private final Map<String, Object> answers = new HashMap<>();
        private final T service;

        @SuppressWarnings("unchecked")
        RecordingService(Class<T> type) {
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                switch (name) {
                    case "toString":
                        return type.getSimpleName() + "Recording";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        break;
                }
                calls.add(name);
                synchronized (arguments) {
                    arguments.computeIfAbsent(name, key -> new ArrayList<>())
                            .add(args == null ? new Object[0] : args.clone());
                }
                if (!answers.containsKey(name)) {
                    // Unstubbed: answer null and let the call be observed. A genuinely unexpected read
                    // still shows up in calls(); a write shows up in writeCalls().
                    return null;
                }
                Object answer = answers.get(name);
                if (answer instanceof RuntimeException failure) {
                    throw failure;
                }
                if (answer instanceof Answer<?> dynamic) {
                    return dynamic.produce(args == null ? new Object[0] : args);
                }
                return answer;
            };
            this.service = (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
        }

        RecordingService<T> on(String methodName, Object answer) {
            answers.put(methodName, answer);
            return this;
        }

        T service() {
            return service;
        }

        /** Every method name invoked, in call order. */
        List<String> calls() {
            return List.copyOf(calls);
        }

        /** The names of the calls that look like state changes. Empty when the path is read-only. */
        List<String> writeCalls() {
            List<String> writes = new ArrayList<>();
            for (String call : calls()) {
                if (looksLikeWrite(call)) {
                    writes.add(call);
                }
            }
            return writes;
        }

        int callCount(String methodName) {
            return argumentsOf(methodName).size();
        }

        List<Object[]> argumentsOf(String methodName) {
            synchronized (arguments) {
                return List.copyOf(arguments.getOrDefault(methodName, Collections.emptyList()));
            }
        }
    }

    /** A stubbed answer computed from the call arguments. */
    @FunctionalInterface
    interface Answer<R> {
        R produce(Object[] args) throws Throwable;
    }

    static boolean looksLikeWrite(String name) {
        return name.startsWith("save") || name.startsWith("update") || name.startsWith("remove")
                || name.startsWith("insert") || name.startsWith("delete") || name.startsWith("add")
                || name.startsWith("put") || name.startsWith("set");
    }

    // ------------------------------------------------------------- scripted llm

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
     * A fake model that replays turns, records every request it was asked to answer, and counts calls.
     *
     * <p>Because the loop builds each {@link LlmRequest} from the session history, inspecting
     * {@link #requests()} is how the tests prove the radius and budget filters really reach the model
     * (the tool result in the request is the exact JSON the model sees).</p>
     */
    static final class ScriptedLlm implements LlmClient {

        private final List<Turn> script;
        private final List<LlmRequest> requests = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger streamCalls = new AtomicInteger();
        private int index;

        ScriptedLlm(Turn... turns) {
            this.script = List.of(turns);
        }

        @Override
        public void stream(LlmRequest request, Consumer<? super LlmStreamEvent> events) {
            streamCalls.incrementAndGet();
            requests.add(request);
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

        List<LlmRequest> requests() {
            return List.copyOf(requests);
        }

        /** The tool result content carried by the last {@code TOOL} message of the last request. */
        JsonNode lastToolResultContent() {
            LlmRequest last = requests.get(requests.size() - 1);
            for (int i = last.messages().size() - 1; i >= 0; i--) {
                var message = last.messages().get(i);
                if (message.role() != Role.TOOL) {
                    continue;
                }
                for (var block : message.content()) {
                    if (block instanceof ToolResultBlock result) {
                        return result.content();
                    }
                }
            }
            throw new IllegalStateException("最后一次模型请求里没有 TOOL 结果消息");
        }
    }

    static JsonNode json(String raw) {
        try {
            return new ObjectMapper().readTree(raw);
        } catch (Exception broken) {
            throw new IllegalArgumentException(broken);
        }
    }
}
