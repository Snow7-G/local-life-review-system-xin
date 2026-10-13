package dev.lifeagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/** Streaming adapter for Anthropic's {@code /v1/messages} protocol. */
public final class AnthropicMessagesAdapter extends AbstractHttpLlmAdapter {

    public AnthropicMessagesAdapter(LlmHttpConfig config) {
        super(config);
    }

    public AnthropicMessagesAdapter(
            LlmHttpConfig config,
            HttpClient httpClient,
            Sleeper sleeper,
            DoubleSupplier randomSource) {
        super(config, httpClient, sleeper, randomSource);
    }

    @Override
    protected JsonNode createRequestBody(LlmRequest request) {
        requireModel(request);
        ObjectNode root = mapper.createObjectNode();
        root.put("model", request.model());
        root.put("max_tokens", request.maxTokens());
        root.put("stream", true);
        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        }

        StringBuilder system = new StringBuilder();
        if (request.systemPrompt() != null && !request.systemPrompt().isEmpty()) {
            system.append(request.systemPrompt());
        }
        ArrayNode messages = root.putArray("messages");
        for (Message message : request.messages()) {
            if (message.role() == Role.SYSTEM) {
                appendSystemText(system, message);
                continue;
            }
            ObjectNode encoded = messages.addObject();
            encoded.put("role", message.role() == Role.ASSISTANT ? "assistant" : "user");
            ArrayNode content = encoded.putArray("content");
            for (ContentBlock block : message.content()) {
                switch (block) {
                    case TextBlock text -> content.addObject()
                            .put("type", "text")
                            .put("text", text.text());
                    case ToolUseBlock toolUse -> {
                        ObjectNode value = content.addObject();
                        value.put("type", "tool_use");
                        value.put("id", toolUse.id());
                        value.put("name", toolUse.name());
                        value.set("input", toolUse.arguments());
                    }
                    case ToolResultBlock result -> {
                        ObjectNode value = content.addObject();
                        value.put("type", "tool_result");
                        value.put("tool_use_id", result.toolUseId());
                        value.set("content", result.content());
                        value.put("is_error", result.isError());
                    }
                }
            }
        }
        if (!system.isEmpty()) {
            root.put("system", system.toString());
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            request.tools().forEach(tools::add);
        }
        return root;
    }

    @Override
    protected Object newStreamState() {
        return new AnthropicStreamState();
    }

    @Override
    protected void validateStreamComplete(Object streamState) {
        if (!((AnthropicStreamState) streamState).messageStopped) {
            throw new LlmProtocolException("Anthropic SSE stream ended before message_stop");
        }
    }

    @Override
    protected boolean handleSseEvent(
            SseParser.SseEvent event,
            Consumer<? super LlmStreamEvent> consumer,
            Object streamState) {
        AnthropicStreamState state = (AnthropicStreamState) streamState;
        JsonNode root = readEventJson(event);
        String type = root.path("type").asText(event.event());
        return switch (type) {
            case "message_start" -> {
                JsonNode usage = root.path("message").path("usage");
                if (usage.path("input_tokens").canConvertToInt()) {
                    state.inputTokens = usage.path("input_tokens").intValue();
                }
                yield true;
            }
            case "content_block_start" -> {
                onContentStart(root, state, consumer);
                yield true;
            }
            case "content_block_delta" -> {
                onContentDelta(root, state, consumer);
                yield true;
            }
            case "content_block_stop" -> {
                onContentStop(root, state, consumer);
                yield true;
            }
            case "message_delta" -> {
                onMessageDelta(root, state, consumer);
                yield true;
            }
            case "message_stop" -> {
                if (!state.blocks.isEmpty()) {
                    throw new LlmProtocolException(
                            "Anthropic message stopped before all content blocks ended");
                }
                state.messageStopped = true;
                yield false;
            }
            case "ping" -> true;
            case "error" -> throw new LlmProtocolException("Anthropic SSE stream reported an error");
            default -> true;
        };
    }

    private void onContentStart(
            JsonNode root,
            AnthropicStreamState state,
            Consumer<? super LlmStreamEvent> consumer) {
        int index = requiredIndex(root);
        if (state.blocks.containsKey(index)) {
            throw new LlmProtocolException("Duplicate Anthropic content block index " + index);
        }
        JsonNode block = root.path("content_block");
        String blockType = requiredText(block, "type", "Anthropic content block type");
        if ("text".equals(blockType)) {
            state.blocks.put(index, BlockState.text());
            String initial = block.path("text").asText("");
            if (!initial.isEmpty()) {
                consumer.accept(new TextDelta(initial));
            }
            return;
        }
        if (!"tool_use".equals(blockType)) {
            state.blocks.put(index, BlockState.other());
            return;
        }
        String id = requiredText(block, "id", "Anthropic tool-use id");
        String name = requiredText(block, "name", "Anthropic tool-use name");
        state.blocks.put(index, BlockState.tool(id));
        consumer.accept(new ToolUseStart(id, name));
        JsonNode input = block.get("input");
        if (input != null && !input.isNull() && !(input.isObject() && input.isEmpty())) {
            consumer.accept(new ToolUseArgumentsDelta(
                    id, writeJson(input, "Anthropic initial tool arguments")));
        }
    }

    private void onContentDelta(
            JsonNode root,
            AnthropicStreamState state,
            Consumer<? super LlmStreamEvent> consumer) {
        int index = requiredIndex(root);
        BlockState block = state.blocks.get(index);
        if (block == null) {
            throw new LlmProtocolException("Delta for unknown Anthropic content block " + index);
        }
        JsonNode delta = root.path("delta");
        String type = requiredText(delta, "type", "Anthropic content delta type");
        if ("text_delta".equals(type)) {
            consumer.accept(new TextDelta(delta.path("text").asText("")));
        } else if ("input_json_delta".equals(type)) {
            if (block.toolUseId == null) {
                throw new LlmProtocolException(
                        "Tool-argument delta belongs to a non-tool Anthropic content block");
            }
            consumer.accept(new ToolUseArgumentsDelta(
                    block.toolUseId, delta.path("partial_json").asText("")));
        }
    }

    private void onContentStop(
            JsonNode root,
            AnthropicStreamState state,
            Consumer<? super LlmStreamEvent> consumer) {
        int index = requiredIndex(root);
        BlockState block = state.blocks.remove(index);
        if (block == null) {
            throw new LlmProtocolException("Stop for unknown Anthropic content block " + index);
        }
        if (block.toolUseId != null) {
            consumer.accept(new ToolUseEnd(block.toolUseId));
        }
    }

    private static void onMessageDelta(
            JsonNode root,
            AnthropicStreamState state,
            Consumer<? super LlmStreamEvent> consumer) {
        String rawReason = root.path("delta").path("stop_reason").isTextual()
                ? root.path("delta").path("stop_reason").asText()
                : null;
        StopReason reason = rawReason == null ? null : switch (rawReason) {
            case "end_turn" -> StopReason.END_TURN;
            case "tool_use" -> StopReason.TOOL_USE;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            case "stop_sequence" -> StopReason.STOP_SEQUENCE;
            default -> StopReason.UNKNOWN;
        };
        JsonNode usageNode = root.path("usage");
        TokenUsage usage = usageNode.path("output_tokens").canConvertToInt()
                ? new TokenUsage(state.inputTokens, usageNode.path("output_tokens").intValue())
                : null;
        if (reason != null || usage != null) {
            consumer.accept(new MessageDelta(reason, usage));
        }
    }

    private static int requiredIndex(JsonNode root) {
        if (!root.path("index").canConvertToInt()) {
            throw new LlmProtocolException("Anthropic content event is missing an integer index");
        }
        return root.path("index").intValue();
    }

    private static String requiredText(JsonNode parent, String field, String description) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new LlmProtocolException(description + " must be a non-blank string");
        }
        return value.textValue();
    }

    private static void appendSystemText(StringBuilder system, Message message) {
        for (ContentBlock block : message.content()) {
            if (!(block instanceof TextBlock text)) {
                throw new LlmProtocolException("Anthropic system messages may contain only text");
            }
            if (!system.isEmpty()) {
                system.append('\n');
            }
            system.append(text.text());
        }
    }

    private static void requireModel(LlmRequest request) {
        if (request.model() == null) {
            throw new IllegalArgumentException("LLM request model is required");
        }
    }

    private static final class AnthropicStreamState {

        private final Map<Integer, BlockState> blocks = new HashMap<>();
        private int inputTokens;
        private boolean messageStopped;
    }

    private static final class BlockState {

        private final String toolUseId;

        private BlockState(String toolUseId) {
            this.toolUseId = toolUseId;
        }

        private static BlockState text() {
            return new BlockState(null);
        }

        private static BlockState tool(String id) {
            return new BlockState(Objects.requireNonNull(id, "id"));
        }

        private static BlockState other() {
            return new BlockState(null);
        }
    }
}
