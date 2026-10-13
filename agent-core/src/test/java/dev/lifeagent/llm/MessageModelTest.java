package dev.lifeagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void messageAndResponseDefensivelyCopyLists() {
        List<ContentBlock> original = new ArrayList<>();
        original.add(new TextBlock("上海"));
        Message message = new Message(Role.USER, original);
        LlmResponse response = new LlmResponse(original, StopReason.END_TURN, TokenUsage.ZERO);

        original.add(new TextBlock("mutated"));

        assertEquals(1, message.content().size());
        assertEquals(1, response.content().size());
        assertThrows(UnsupportedOperationException.class,
                () -> message.content().add(new TextBlock("forbidden")));
    }

    @Test
    void allJsonNodesAreDefensivelyCopiedOnInputAndOutput() {
        ObjectNode arguments = MAPPER.createObjectNode().put("city", "上海");
        ToolUseBlock toolUse = new ToolUseBlock("call-1", "search", arguments);
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        LlmRequest request = new LlmRequest(
                List.of(), List.of(schema), null, "test-model", 100, 0.2);

        arguments.put("city", "北京");
        schema.put("type", "array");
        JsonNode returnedArguments = toolUse.arguments();
        ((ObjectNode) returnedArguments).put("city", "深圳");
        JsonNode returnedSchema = request.tools().getFirst();
        ((ObjectNode) returnedSchema).put("type", "string");

        assertEquals("上海", toolUse.arguments().path("city").asText());
        assertEquals("object", request.tools().getFirst().path("type").asText());
        assertThrows(UnsupportedOperationException.class,
                () -> request.tools().add(MAPPER.createObjectNode()));
    }
}
