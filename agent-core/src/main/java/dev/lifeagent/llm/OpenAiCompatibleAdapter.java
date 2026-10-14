package dev.lifeagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/** Streaming adapter for OpenAI-compatible {@code /chat/completions} endpoints. */
public final class OpenAiCompatibleAdapter extends AbstractHttpLlmAdapter {

    public OpenAiCompatibleAdapter(LlmHttpConfig config) {
        super(config);
    }

    public OpenAiCompatibleAdapter(
            LlmHttpConfig config,
            HttpClient httpClient,
            Sleeper sleeper,
            DoubleSupplier randomSource) {
        super(config, httpClient, sleeper, randomSource);
    }

    @Override
    protected JsonNode createRequestBody(LlmRequest request) {
        if (request.model() == null) {
            throw new IllegalArgumentException("LLM request model is required");
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("model", request.model());
        root.put("max_tokens", request.maxTokens());
        root.put("stream", true);
        root.set("stream_options", mapper.createObjectNode().put("include_usage", true));
        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        }
        ArrayNode messages = root.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isEmpty()) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }
        for (Message message : request.messages()) {
            encodeMessage(messages, message);
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            request.tools().forEach(tools::add);
        }
        return root;
    }

    @Override
    protected Object newStreamState() {
        return new OpenAiStreamState();
    }

    @Override
    protected void validateStreamComplete(Object streamState) {
        OpenAiStreamState state = (OpenAiStreamState) streamState;
        if (state.done) {
            return;
        }
        // Hardening from the W2-1 review: some OpenAI-compatible gateways close the connection
        // without ever sending the `[DONE]` sentinel. That is tolerated only when the provider has
        // already ended the completion (a finish_reason was seen) and no tool call is still waiting
        // for its arguments; anything else is a truncated stream and must stay loud.
        //
        // TODO(W2-3 循环层): the SSE body itself has no read deadline either - the loop layer that
        // owns the stream must add an idle timeout. Deliberately not implemented here.
        if (state.finished && state.tools.isEmpty()) {
            return;
        }
        throw new LlmProtocolException("OpenAI SSE stream ended before [DONE]");
    }

    @Override
    protected boolean handleSseEvent(
            SseParser.SseEvent event,
            Consumer<? super LlmStreamEvent> consumer,
            Object streamState) {
        OpenAiStreamState state = (OpenAiStreamState) streamState;
        if ("[DONE]".equals(event.data().trim())) {
            finishAllTools(state, consumer);
            state.done = true;
            return false;
        }
        JsonNode root = readEventJson(event);
        for (JsonNode choice : root.path("choices")) {
            int choiceIndex = choice.path("index").canConvertToInt()
                    ? choice.path("index").intValue() : 0;
            JsonNode delta = choice.path("delta");
            JsonNode content = delta.get("content");
            if (content != null && content.isTextual()) {
                consumer.accept(new TextDelta(content.textValue()));
            }
            for (JsonNode toolCall : delta.path("tool_calls")) {
                onToolCallDelta(choiceIndex, toolCall, state, consumer);
            }
            JsonNode finishReason = choice.get("finish_reason");
            if (finishReason != null && finishReason.isTextual()) {
                finishChoiceTools(choiceIndex, state, consumer);
                state.finished = true;
                consumer.accept(new MessageDelta(mapStopReason(finishReason.textValue()), null));
            }
        }
        JsonNode usage = root.get("usage");
        if (usage != null && usage.isObject()
                && usage.path("prompt_tokens").canConvertToInt()
                && usage.path("completion_tokens").canConvertToInt()) {
            consumer.accept(new MessageDelta(null, new TokenUsage(
                    usage.path("prompt_tokens").intValue(),
                    usage.path("completion_tokens").intValue())));
        }
        return true;
    }

    private void encodeMessage(ArrayNode messages, Message message) {
        if (message.role() == Role.TOOL) {
            for (ContentBlock block : message.content()) {
                if (!(block instanceof ToolResultBlock result)) {
                    throw new LlmProtocolException(
                            "OpenAI TOOL messages may contain only tool results");
                }
                JsonNode content = result.content();
                String encodedContent = content.isTextual()
                        ? content.textValue() : writeJson(content, "OpenAI tool result");
                messages.addObject()
                        .put("role", "tool")
                        .put("tool_call_id", result.toolUseId())
                        .put("content", encodedContent);
            }
            return;
        }

        ObjectNode encoded = messages.addObject();
        encoded.put("role", switch (message.role()) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> throw new IllegalStateException("handled above");
        });
        StringBuilder text = new StringBuilder();
        ArrayNode toolCalls = null;
        for (ContentBlock block : message.content()) {
            switch (block) {
                case TextBlock textBlock -> text.append(textBlock.text());
                case ToolUseBlock toolUse -> {
                    if (message.role() != Role.ASSISTANT) {
                        throw new LlmProtocolException(
                                "OpenAI tool uses must belong to an assistant message");
                    }
                    if (toolCalls == null) {
                        toolCalls = encoded.putArray("tool_calls");
                    }
                    ObjectNode call = toolCalls.addObject();
                    call.put("id", toolUse.id());
                    call.put("type", "function");
                    call.set("function", mapper.createObjectNode()
                            .put("name", toolUse.name())
                            .put("arguments", writeJson(
                                    toolUse.arguments(), "OpenAI tool arguments")));
                }
                case ToolResultBlock ignored -> throw new LlmProtocolException(
                        "OpenAI tool results must belong to a TOOL message");
            }
        }
        if (!text.isEmpty()) {
            encoded.put("content", text.toString());
        } else {
            encoded.putNull("content");
        }
    }

    private static void onToolCallDelta(
            int choiceIndex,
            JsonNode toolCall,
            OpenAiStreamState state,
            Consumer<? super LlmStreamEvent> consumer) {
        if (!toolCall.path("index").canConvertToInt()) {
            throw new LlmProtocolException("OpenAI tool-call delta is missing an integer index");
        }
        int toolIndex = toolCall.path("index").intValue();
        ToolKey key = new ToolKey(choiceIndex, toolIndex);
        OpenAiToolState tool = state.tools.computeIfAbsent(key, ignored -> new OpenAiToolState());
        updateStableValue(toolCall.get("id"), tool.id, value -> tool.id = value, "id");
        JsonNode function = toolCall.path("function");
        updateStableValue(function.get("name"), tool.name, value -> tool.name = value, "name");
        if (!tool.started && tool.id != null && tool.name != null) {
            tool.started = true;
            consumer.accept(new ToolUseStart(tool.id, tool.name));
        }
        JsonNode arguments = function.get("arguments");
        if (arguments != null && arguments.isTextual()) {
            if (!tool.started) {
                throw new LlmProtocolException(
                        "OpenAI tool arguments arrived before tool id and name");
            }
            consumer.accept(new ToolUseArgumentsDelta(tool.id, arguments.textValue()));
        }
    }

    private static void updateStableValue(
            JsonNode candidate,
            String current,
            Consumer<String> setter,
            String field) {
        if (candidate == null || candidate.isNull()) {
            return;
        }
        if (!candidate.isTextual() || candidate.textValue().isBlank()) {
            throw new LlmProtocolException("OpenAI tool-call " + field + " must be non-blank");
        }
        String value = candidate.textValue();
        if (current != null && !current.equals(value)) {
            throw new LlmProtocolException("OpenAI tool-call " + field + " changed mid-stream");
        }
        setter.accept(value);
    }

    private static void finishChoiceTools(
            int choiceIndex,
            OpenAiStreamState state,
            Consumer<? super LlmStreamEvent> consumer) {
        Iterator<Map.Entry<ToolKey, OpenAiToolState>> entries = state.tools.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<ToolKey, OpenAiToolState> entry = entries.next();
            if (entry.getKey().choiceIndex == choiceIndex) {
                finishTool(entry.getValue(), consumer);
                entries.remove();
            }
        }
    }

    private static void finishAllTools(
            OpenAiStreamState state,
            Consumer<? super LlmStreamEvent> consumer) {
        state.tools.values().forEach(tool -> finishTool(tool, consumer));
        state.tools.clear();
    }

    private static void finishTool(
            OpenAiToolState tool,
            Consumer<? super LlmStreamEvent> consumer) {
        if (!tool.started) {
            throw new LlmProtocolException("OpenAI tool call ended before id and name arrived");
        }
        consumer.accept(new ToolUseEnd(tool.id));
    }

    private static StopReason mapStopReason(String reason) {
        return switch (reason) {
            case "stop" -> StopReason.END_TURN;
            case "tool_calls", "function_call" -> StopReason.TOOL_USE;
            case "length" -> StopReason.MAX_TOKENS;
            case "content_filter" -> StopReason.CONTENT_FILTER;
            default -> StopReason.UNKNOWN;
        };
    }

    private static final class OpenAiStreamState {

        private final Map<ToolKey, OpenAiToolState> tools = new LinkedHashMap<>();
        private boolean finished;
        private boolean done;
    }

    private record ToolKey(int choiceIndex, int toolIndex) {
    }

    private static final class OpenAiToolState {

        private String id;
        private String name;
        private boolean started;
    }
}
