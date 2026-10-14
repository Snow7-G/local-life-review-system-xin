package dev.lifeagent.loop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.lifeagent.llm.StopReason;
import dev.lifeagent.llm.TokenUsage;
import dev.lifeagent.llm.ToolUseArgumentsDelta;
import dev.lifeagent.llm.ToolUseBlock;
import dev.lifeagent.llm.ToolUseEnd;
import dev.lifeagent.llm.ToolUseStart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rebuilds one complete model answer from a stream's tool-call events, in the order the calls
 * started.
 *
 * <p>Argument fragments are only meaningful once the call has ended, so a call is emitted only when
 * it started, ended and its accumulated JSON parses as an object. A call left half-finished by a
 * dropped stream is reported through {@link #hasUnusableCall()} so the loop can retry the step
 * instead of inventing a tool call with empty arguments.</p>
 */
final class AuthenticToolCalls {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, ToolUseStart> started = new LinkedHashMap<>();
    private final Map<String, StringBuilder> fragments = new LinkedHashMap<>();
    private final Set<String> ended = new LinkedHashSet<>();

    void onStart(ToolUseStart start) {
        started.putIfAbsent(start.id(), start);
        fragments.putIfAbsent(start.id(), new StringBuilder());
    }

    void onArguments(ToolUseArgumentsDelta delta) {
        fragments.computeIfAbsent(delta.id(), ignored -> new StringBuilder()).append(delta.partialJson());
    }

    void onEnd(ToolUseEnd end) {
        if (started.containsKey(end.id())) {
            ended.add(end.id());
        }
    }

    /** @return the calls that are complete and parseable, in the order they started. */
    List<ToolUseBlock> calls() {
        List<ToolUseBlock> calls = new ArrayList<>();
        for (Map.Entry<String, ToolUseStart> entry : started.entrySet()) {
            String id = entry.getKey();
            if (!ended.contains(id)) {
                continue;
            }
            JsonNode arguments = parse(fragments.getOrDefault(id, new StringBuilder()).toString());
            if (arguments == null) {
                continue;
            }
            calls.add(new ToolUseBlock(id, entry.getValue().name(), arguments));
        }
        return calls;
    }

    /**
     * @return {@code true} when the stream left a tool call that cannot be run - it never ended, or
     *         its arguments are not a JSON object
     */
    boolean hasUnusableCall() {
        for (Map.Entry<String, ToolUseStart> entry : started.entrySet()) {
            String id = entry.getKey();
            if (!ended.contains(id)) {
                return true;
            }
            if (parse(fragments.getOrDefault(id, new StringBuilder()).toString()) == null) {
                return true;
            }
        }
        return false;
    }

    /** @return the stop reason to report when the provider did not send one. */
    StopReason inferredStopReason(boolean hasText) {
        if (!calls().isEmpty()) {
            return StopReason.TOOL_USE;
        }
        return hasText ? StopReason.END_TURN : StopReason.UNKNOWN;
    }

    static TokenUsage orZero(TokenUsage usage) {
        return usage == null ? TokenUsage.ZERO : usage;
    }

    /** @return the parsed object, or {@code null} when the fragments are not a complete JSON object. */
    private static JsonNode parse(String raw) {
        if (raw.isBlank()) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException invalid) {
            return null;
        }
    }
}
