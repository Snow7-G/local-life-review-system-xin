package dev.lifeagent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps raw tool-argument fragments separated by tool id and parses exactly once, at
 * {@link ToolUseEnd}. No append or read-before-end path calls the JSON parser.
 */
public final class ToolUseAccumulator {

    private final ArgumentsParser parser;
    private final Map<String, PendingTool> pending = new HashMap<>();
    private final Map<String, ToolUseBlock> completed = new HashMap<>();
    private final Map<String, LlmProtocolException> failed = new HashMap<>();

    public ToolUseAccumulator() {
        this(new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
    }

    public ToolUseAccumulator(ObjectMapper mapper) {
        this(withStrictParsing(Objects.requireNonNull(mapper, "mapper"))::readTree);
    }

    /**
     * Enforces this class's documented invariant regardless of the mapper that was injected:
     * a caller's lax mapper must not be able to reintroduce silent truncation of trailing
     * content. The flag is enabled on the passed instance because the parser contract here is
     * a single complete JSON document per tool call.
     */
    private static ObjectMapper withStrictParsing(ObjectMapper mapper) {
        return mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    ToolUseAccumulator(ArgumentsParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public synchronized void start(String id, String name) {
        ToolUseStart event = new ToolUseStart(id, name);
        if (pending.containsKey(id) || completed.containsKey(id) || failed.containsKey(id)) {
            throw new IllegalStateException("tool use already exists: " + id);
        }
        pending.put(id, new PendingTool(event.name()));
    }

    public synchronized void append(String id, String partialJson) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(partialJson, "partialJson");
        PendingTool tool = pending.get(id);
        if (tool == null) {
            throw stateFailure(id);
        }
        tool.rawArguments().append(partialJson);
    }

    /** Completes one tool and performs the first and only parse of its accumulated JSON. */
    public synchronized ToolUseBlock complete(String id) {
        Objects.requireNonNull(id, "id");
        PendingTool tool = pending.remove(id);
        if (tool == null) {
            throw stateFailure(id);
        }
        String rawJson = tool.rawArguments().isEmpty() ? "{}" : tool.rawArguments().toString();
        try {
            JsonNode arguments = parser.parse(rawJson);
            if (arguments == null || !arguments.isObject()) {
                throw new LlmProtocolException(
                        "Tool arguments for " + id + " must be a JSON object");
            }
            ToolUseBlock result = new ToolUseBlock(id, tool.name(), arguments);
            completed.put(id, result);
            return result;
        } catch (JsonProcessingException e) {
            LlmProtocolException failure = new LlmProtocolException(
                    "Invalid complete JSON arguments for tool use " + id, e);
            failed.put(id, failure);
            throw failure;
        } catch (IOException e) {
            LlmProtocolException failure = new LlmProtocolException(
                    "Could not parse complete JSON arguments for tool use " + id, e);
            failed.put(id, failure);
            throw failure;
        } catch (LlmProtocolException e) {
            failed.put(id, e);
            throw e;
        }
    }

    /**
     * Returns a defensive copy only after completion. Calling this while fragments are still
     * arriving is an explicit misuse and fails rather than exposing an incomplete value.
     */
    public synchronized JsonNode arguments(String id) {
        Objects.requireNonNull(id, "id");
        ToolUseBlock result = completed.get(id);
        if (result != null) {
            return result.arguments();
        }
        throw stateFailure(id);
    }

    /** Applies only tool-related stream events and returns a value solely for an end event. */
    public synchronized Optional<ToolUseBlock> accept(LlmStreamEvent event) {
        Objects.requireNonNull(event, "event");
        return switch (event) {
            case ToolUseStart start -> {
                start(start.id(), start.name());
                yield Optional.empty();
            }
            case ToolUseArgumentsDelta delta -> {
                append(delta.id(), delta.partialJson());
                yield Optional.empty();
            }
            case ToolUseEnd end -> Optional.of(complete(end.id()));
            default -> throw new IllegalArgumentException(
                    "event is not a tool-use accumulation event: " + event.getClass().getSimpleName());
        };
    }

    private IllegalStateException stateFailure(String id) {
        if (pending.containsKey(id)) {
            return new IllegalStateException("tool arguments are incomplete before ToolUseEnd: " + id);
        }
        if (failed.containsKey(id)) {
            return new IllegalStateException("tool argument parsing failed at ToolUseEnd: " + id,
                    failed.get(id));
        }
        if (completed.containsKey(id)) {
            return new IllegalStateException("tool use is already complete: " + id);
        }
        return new IllegalStateException("unknown tool use: " + id);
    }

    @FunctionalInterface
    interface ArgumentsParser {
        JsonNode parse(String completeJson) throws IOException;
    }

    private record PendingTool(String name, StringBuilder rawArguments) {

        private PendingTool(String name) {
            this(name, new StringBuilder());
        }
    }
}
