package dev.lifeagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registration, export and execution of tools, including the "validation before the business call"
 * contract (the zero-call proof lives in {@link ToolRegistryZeroCallTest}).
 */
class ToolRegistryTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Test
    void registersEveryAnnotatedMethodOfAProviderInNameOrder() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(new NoopCatalog()));

        assertEquals(List.of("search_nearby", "shop_detail", "voucher_list"),
                registry.definitions().stream().map(ToolDefinition::name).toList());
        assertEquals(3, registry.size());
        assertTrue(registry.find("shop_detail").isPresent());
        assertFalse(registry.find("delete_shop").isPresent());
    }

    @Test
    void ignoresPublicMethodsWithoutTheAnnotation() {
        ToolRegistry registry = new ToolRegistry().registerTools(new MixedProvider());

        assertEquals(List.of("mixed"), registry.definitions().stream().map(ToolDefinition::name).toList());
    }

    @Test
    void unknownToolLookupExplainsItself() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(new NoopCatalog()));

        UnknownToolException failure = assertThrows(UnknownToolException.class, () -> registry.tool("nope"));
        assertEquals("nope", failure.toolName());
        assertTrue(failure.getMessage().contains("nope"));
    }

    @Test
    void duplicateNameIsRejected() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(new NoopCatalog()));

        DuplicateToolException failure = assertThrows(DuplicateToolException.class,
                () -> registry.registerTools(new ShopTools(new NoopCatalog())));
        assertEquals("search_nearby", failure.toolName());
        assertEquals(3, registry.size(), "a rejected registration must not change the registry");
    }

    @Test
    void registeredDefinitionsCarryTheirDeclarationMetadata() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(new NoopCatalog()));

        ToolDefinition search = registry.tool("search_nearby");
        assertEquals(SearchNearbyArgs.class, search.argumentsType());
        assertEquals(Permission.READ, search.permission());
        assertFalse(search.description().isBlank());
        assertEquals(List.of("category", "radius"), fieldNames(search.argumentsSchema().get("required")));
    }

    @Test
    void exportsTheOpenAiWireShape() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(new NoopCatalog()));

        List<JsonNode> tools = registry.modelVisibleTools();
        assertEquals(3, tools.size());
        JsonNode function = tools.get(0).get("function");
        assertEquals("function", tools.get(0).get("type").asText());
        assertEquals("search_nearby", function.get("name").asText());
        assertEquals(registry.tool("search_nearby").description(), function.get("description").asText());
        assertEquals(registry.tool("search_nearby").argumentsSchema(), function.get("parameters"));
    }

    @Test
    void exportsTheAnthropicWireShape() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(new NoopCatalog()));

        List<JsonNode> tools = registry.anthropicModelVisibleTools();
        assertEquals(3, tools.size());
        assertEquals("search_nearby", tools.get(0).get("name").asText());
        assertFalse(tools.get(0).has("type"), "Anthropic takes name/description/input_schema only");
        assertEquals(registry.tool("search_nearby").argumentsSchema(), tools.get(0).get("input_schema"));
    }

    @Test
    void exportedSchemasAndDefinitionsAreDefensiveCopies() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(new NoopCatalog()));

        ObjectNode exported = (ObjectNode) registry.modelVisibleTools().get(0).get("function").get("parameters");
        exported.remove("required");
        exported.put("additionalProperties", true);

        ObjectNode again = registry.tool("search_nearby").argumentsSchema();
        assertNotNull(again.get("required"));
        assertFalse(again.get("additionalProperties").asBoolean());

        assertThrows(UnsupportedOperationException.class,
                () -> registry.definitions().add(registry.tool("search_nearby")));
    }

    @Test
    void invokesAToolAndSerializesItsResult() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ComplexTools());

        ObjectNode arguments = NODES.objectNode()
                .put("name", "老王")
                .put("count", 2);
        arguments.putArray("inners").addObject().put("id", 7).put("color", "RED");
        arguments.putObject("scores").put("辣度", 3);
        arguments.put("optional", 5);

        ToolSuccess success = assertInstanceOf(ToolSuccess.class, registry.invoke("complex", arguments));
        assertEquals("complex", success.toolName());
        assertFalse(success.isError());
        assertEquals("老王", success.result().get("name").asText());
        assertEquals(7, success.result().get("inners").get(0).get("id").asLong());
        assertEquals(3, success.result().get("scores").get("辣度").asInt());
    }

    @Test
    void unknownToolIsReportedAsAFailureInsteadOfThrowing() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(new NoopCatalog()));

        ToolFailure failure = assertInstanceOf(ToolFailure.class,
                registry.invoke("delete_everything", NODES.objectNode()));
        assertEquals(ToolErrorKind.UNKNOWN_TOOL, failure.errorKind());
        assertTrue(failure.isError());
        assertTrue(failure.message().contains("delete_everything"));
    }

    @Test
    void missingRequiredArgumentIsReportedBeforeTheHandlerRuns() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(new NoopCatalog()));

        ToolFailure failure = assertInstanceOf(ToolFailure.class,
                registry.invoke("search_nearby", NODES.objectNode().put("category", "火锅")));
        assertEquals(ToolErrorKind.INVALID_ARGUMENTS, failure.errorKind());
        assertEquals("参数校验失败：缺少必填参数：radius", failure.message());
    }

    @Test
    void wrongArgumentTypeIsReportedWithItsPath() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(new NoopCatalog()));

        ToolFailure failure = assertInstanceOf(ToolFailure.class, registry.invoke("search_nearby",
                NODES.objectNode().put("category", "火锅").put("radius", "很远")));
        assertEquals(ToolErrorKind.INVALID_ARGUMENTS, failure.errorKind());
        assertTrue(failure.message().contains("参数.radius 类型不符"), failure.message());
        assertTrue(failure.message().contains("整数"), failure.message());
    }

    @Test
    void unknownArgumentIsReportedInsteadOfBeingIgnored() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(new NoopCatalog()));

        ToolFailure failure = assertInstanceOf(ToolFailure.class, registry.invoke("search_nearby",
                NODES.objectNode().put("category", "火锅").put("radius", 500).put("colour", "red")));
        assertEquals(ToolErrorKind.INVALID_ARGUMENTS, failure.errorKind());
        assertEquals("参数校验失败：不认识的参数：colour", failure.message());
    }

    @Test
    void nonObjectArgumentsAreRejected() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(new NoopCatalog()));

        ToolFailure failure = assertInstanceOf(ToolFailure.class,
                registry.invoke("search_nearby", NODES.textNode("火锅")));
        assertEquals(ToolErrorKind.INVALID_ARGUMENTS, failure.errorKind());
        assertTrue(failure.message().contains("参数 类型不符"), failure.message());
    }

    @Test
    void nestedProblemsCarryTheirPath() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ComplexTools());

        ObjectNode arguments = NODES.objectNode().put("name", "老王").put("count", 1);
        arguments.putArray("inners").addObject().put("color", "BLUE");

        ToolFailure failure = assertInstanceOf(ToolFailure.class, registry.invoke("complex", arguments));
        assertEquals(ToolErrorKind.INVALID_ARGUMENTS, failure.errorKind());
        assertTrue(failure.message().contains("缺少必填参数：参数.inners[0].id"), failure.message());
        assertTrue(failure.message().contains("参数.inners[0].color 取值不合法"), failure.message());
    }

    @Test
    void bindingFailureIsReportedAsInvalidArguments() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(new NoopCatalog()));

        // 9999 is a valid integer, so the schema passes and the record's own bound is what rejects it.
        ToolFailure failure = assertInstanceOf(ToolFailure.class, registry.invoke("search_nearby",
                NODES.objectNode().put("category", "火锅").put("radius", 9999)));
        assertEquals(ToolErrorKind.INVALID_ARGUMENTS, failure.errorKind());
        assertTrue(failure.message().contains("SearchNearbyArgs"), failure.message());
    }

    @Test
    void throwingHandlerBecomesAnExecutionError() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ThrowingTools());

        ToolFailure failure = assertInstanceOf(ToolFailure.class,
                registry.invoke("boom", NODES.objectNode().put("id", 1)));
        assertEquals(ToolErrorKind.EXECUTION_ERROR, failure.errorKind());
        assertTrue(failure.message().contains("炸了"), failure.message());
        assertTrue(failure.message().contains("IllegalStateException"), failure.message());
    }

    @Test
    void nullResultIsSerializedAsJsonNull() {
        ToolRegistry registry = new ToolRegistry().registerTools(new NullReturningTools());

        ToolSuccess success = assertInstanceOf(ToolSuccess.class,
                registry.invoke("nothing", NODES.objectNode().put("value", "valid")));
        assertTrue(success.result().isNull());
    }

    @Test
    void invocationRequiresArguments() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(new NoopCatalog()));

        assertThrows(NullPointerException.class, () -> registry.invoke("search_nearby", null));
        assertThrows(NullPointerException.class, () -> registry.register(null));
        assertThrows(NullPointerException.class, () -> registry.registerTools(null));
        assertThrows(NullPointerException.class, () -> new ToolRegistry(null));
    }

    @Test
    void invokeCanTargetADefinitionDirectly() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(new NoopCatalog()));
        ToolDefinition search = registry.tool("search_nearby");

        ToolSuccess success = assertInstanceOf(ToolSuccess.class, registry.invoke(search,
                NODES.objectNode().put("category", "KTV").put("radius", 300)));
        assertEquals("search_nearby", success.toolName());
    }

    @Test
    void invalidDeclarationsFailFastAtRegistration() {
        assertThrows(ToolDeclarationException.class,
                () -> new ToolRegistry().registerTools(new VoidToolProvider()));
        assertThrows(ToolDeclarationException.class,
                () -> new ToolRegistry().registerTools(new NonRecordToolProvider()));
        assertThrows(ToolDeclarationException.class,
                () -> new ToolRegistry().registerTools(new TwoParameterToolProvider()));
        assertThrows(ToolDeclarationException.class,
                () -> new ToolRegistry().registerTools(new BlankNameToolProvider()));
        assertThrows(ToolDeclarationException.class, () -> new ToolRegistry()
                .register(ToolDefinition.of("raw", "描述", Permission.READ, String.class, args -> args)));
    }

    @Test
    void definitionRejectsMissingMetadataAndNonRecordArguments() {
        assertThrows(ToolDeclarationException.class,
                () -> ToolDefinition.of("", "描述", Permission.READ, BareArgs.class, args -> args));
        assertThrows(ToolDeclarationException.class,
                () -> ToolDefinition.of("x", "  ", Permission.READ, BareArgs.class, args -> args));
        assertThrows(NullPointerException.class,
                () -> ToolDefinition.of("x", "描述", null, BareArgs.class, args -> args));
        assertThrows(NullPointerException.class,
                () -> ToolDefinition.of("x", "描述", Permission.READ, BareArgs.class, null));
        assertThrows(ToolDeclarationException.class,
                () -> ToolDefinition.of("x", "描述", Permission.READ, String.class, args -> args));
    }

    @Test
    void schemaHandedToTheDefinitionIsCopiedOnTheWayIn() {
        ObjectNode schema = JsonSchemaGenerator.schemaFor(BareArgs.class);
        ToolDefinition definition = new ToolDefinition("bare", "描述", Permission.READ, BareArgs.class, schema,
                args -> args);

        schema.remove("required");
        assertNotNull(definition.argumentsSchema().get("required"));

        ToolDefinition second = new ToolDefinition("bare2", "描述", Permission.READ, BareArgs.class,
                JsonSchemaGenerator.schemaFor(BareArgs.class), args -> args);
        assertNotNull(second.argumentsSchema().get("required"));
        assertEquals(second.argumentsSchema(), JsonSchemaGenerator.schemaFor(BareArgs.class));
    }

    @Test
    void outcomeFlagsErrors() {
        ToolSuccess success = new ToolSuccess("t", NODES.objectNode());
        ToolFailure failure = new ToolFailure("t", ToolErrorKind.UNKNOWN_TOOL, "未知工具：t");

        assertFalse(success.isError());
        assertTrue(failure.isError());
        assertSame(ToolErrorKind.UNKNOWN_TOOL, failure.errorKind());
        assertThrows(NullPointerException.class, () -> new ToolFailure("t", null, "m"));
        assertThrows(NullPointerException.class, () -> new ToolSuccess(null, NODES.objectNode()));
        assertThrows(NullPointerException.class, () -> new ToolSuccess("t", null));
    }

    private static List<String> fieldNames(JsonNode array) {
        List<String> names = new java.util.ArrayList<>();
        array.forEach(node -> names.add(node.asText()));
        return names;
    }

    enum Color { RED, GREEN }

    record Inner(long id, @Desc("颜色") Color color) {
    }

    record ComplexArgs(
            @Desc("名称") String name,
            int count,
            List<Inner> inners,
            Map<String, Integer> scores,
            Integer optional) {
    }

    record BoomArgs(long id) {
    }

    record BareArgs(String value) {
    }

    /** A provider whose annotated method returns its own arguments, so results are easy to assert. */
    static final class ComplexTools {

        @AgentTool(name = "complex", description = "测试用：回显参数", permission = Permission.READ)
        public ComplexArgs complex(ComplexArgs args) {
            return args;
        }
    }

    static final class ThrowingTools {

        @AgentTool(name = "boom", description = "测试用：总是抛异常", permission = Permission.READ)
        public String boom(BoomArgs args) {
            throw new IllegalStateException("炸了");
        }
    }

    static final class NullReturningTools {

        @AgentTool(name = "nothing", description = "测试用：返回 null", permission = Permission.READ)
        public String nothing(BareArgs args) {
            return null;
        }
    }

    static final class MixedProvider {

        @AgentTool(name = "mixed", description = "测试用：只有一个方法带注解", permission = Permission.READ)
        public BareArgs mixed(BareArgs args) {
            return args;
        }

        public String helper() {
            return "not a tool";
        }
    }

    static final class VoidToolProvider {

        @AgentTool(name = "void_tool", description = "测试用：返回 void", permission = Permission.READ)
        public void nothing(BareArgs args) {
        }
    }

    static final class NonRecordToolProvider {

        @AgentTool(name = "string_tool", description = "测试用：参数不是 record", permission = Permission.READ)
        public String take(String args) {
            return args;
        }
    }

    static final class TwoParameterToolProvider {

        @AgentTool(name = "two_params", description = "测试用：两个参数", permission = Permission.READ)
        public String take(BareArgs first, BareArgs second) {
            return "x";
        }
    }

    static final class BlankNameToolProvider {

        @AgentTool(name = "   ", description = "测试用：工具名为空白", permission = Permission.READ)
        public String take(BareArgs args) {
            return "x";
        }
    }

    /** Does nothing; used where only the tool declarations matter. */
    static final class NoopCatalog implements ShopCatalog {

        @Override
        public List<ShopBrief> searchNearby(SearchNearbyArgs args) {
            return List.of();
        }

        @Override
        public ShopDetail shopDetail(ShopDetailArgs args) {
            return new ShopDetail("1", "店", "火锅", "地址", 60, 4.5);
        }

        @Override
        public List<VoucherBrief> voucherList(VoucherListArgs args) {
            return List.of();
        }
    }
}
