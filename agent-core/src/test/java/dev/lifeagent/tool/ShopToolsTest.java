package dev.lifeagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The W2-2 tool contract, checked against the card: three read-only tools, {@link Permission#READ}
 * for all of them, model-facing descriptions that say when to use and when not to use them, and
 * nothing that looks like a write operation anywhere in the payload the model sees.
 */
class ShopToolsTest {

    /** Anything that would let the model change state must not appear in a READ tool's payload. */
    private static final Pattern WRITE_VERBS = Pattern.compile(
            "order|refund|book|cancel|create|update|delete|下单|退款|取消|创建",
            Pattern.CASE_INSENSITIVE);

    private static ToolRegistry registry(ShopCatalog catalog) {
        return new ToolRegistry().registerTools(new ShopTools(catalog));
    }

    @Test
    void declaresExactlyTheThreeReadOnlyTools() {
        ToolRegistry registry = registry(new NoopCatalog());

        assertEquals(List.of("search_nearby", "shop_detail", "voucher_list"), ShopTools.READ_ONLY_TOOL_NAMES);
        assertEquals(ShopTools.READ_ONLY_TOOL_NAMES,
                registry.definitions().stream().map(ToolDefinition::name).toList());
    }

    @Test
    void everyToolIsReadOnlyAndThereAreExactlyThreeLevels() {
        ToolRegistry registry = registry(new NoopCatalog());

        assertEquals(3, registry.size());
        for (ToolDefinition definition : registry.definitions()) {
            assertEquals(Permission.READ, definition.permission(), definition.name() + " must be READ");
        }
        assertEquals(3, Permission.values().length, "the card fixes exactly three permission levels");
        assertFalse(registry.definitions().stream()
                .anyMatch(d -> d.permission() == Permission.WRITE || d.permission() == Permission.HIGH_RISK));
    }

    @Test
    void descriptionsSayWhenToUseAndWhenNotToUse() {
        ToolRegistry registry = registry(new NoopCatalog());

        for (ToolDefinition definition : registry.definitions()) {
            String description = definition.description();
            assertFalse(description.isBlank(), definition.name());
            assertTrue(description.contains("用"), definition.name() + ": " + description);
            assertTrue(description.contains("别用"), definition.name() + ": " + description);
        }
    }

    @Test
    void searchNearbyDescriptionIsTheOneFromTheCard() {
        ToolRegistry registry = registry(new NoopCatalog());

        assertEquals("按位置和品类搜附近的店。用户提到距离、附近、周边时用；只问某家店的详情时别用",
                registry.tool("search_nearby").description());
    }

    @Test
    void thePayloadTheModelSeesHasNoWriteOperation() {
        ToolRegistry registry = registry(new NoopCatalog());

        assertNoWriteVerbs(registry.modelVisibleTools().toString(), "OpenAI payload");
        assertNoWriteVerbs(registry.anthropicModelVisibleTools().toString(), "Anthropic payload");
    }

    @Test
    void argumentSchemasHaveNoWriteOperation() {
        // The declarations carry no write verb, so neither does anything derived from them.
        ToolRegistry registry = registry(new NoopCatalog());

        assertNoWriteVerbs(registry.tool("shop_detail").argumentsSchema().toString(), "shop_detail schema");
        assertNoWriteVerbs(registry.tool("voucher_list").argumentsSchema().toString(), "voucher_list schema");
    }

    @Test
    void schemasAreClosedObjectsWithDescribedProperties() {
        ToolRegistry registry = registry(new NoopCatalog());

        for (ToolDefinition definition : registry.definitions()) {
            JsonNode schema = definition.argumentsSchema();
            assertEquals("object", schema.get("type").asText(), definition.name());
            assertFalse(schema.get("additionalProperties").asBoolean(), definition.name());
            assertTrue(schema.get("required").size() > 0, definition.name());
            assertTrue(schema.get("properties").size() > 0, definition.name());
            schema.get("properties").fields().forEachRemaining(property -> {
                assertFalse(property.getValue().get("description").asText().isBlank(),
                        definition.name() + "." + property.getKey() + " must be described for the model");
                assertNoWriteVerbs(property.getKey(), "property name");
            });
        }
    }

    @Test
    void argumentShapesMatchTheCard() {
        ToolRegistry registry = registry(new NoopCatalog());

        assertEquals(List.of("category", "radius", "maxPrice"),
                propertyNames(registry.tool("search_nearby").argumentsSchema()));
        assertEquals(List.of("shopId"), propertyNames(registry.tool("shop_detail").argumentsSchema()));
        assertEquals(List.of("shopId"), propertyNames(registry.tool("voucher_list").argumentsSchema()));
        assertEquals(List.of("category", "radius"), requiredNames(registry.tool("search_nearby").argumentsSchema()));
        assertEquals(List.of("shopId"), requiredNames(registry.tool("shop_detail").argumentsSchema()));
        assertEquals(List.of("shopId"), requiredNames(registry.tool("voucher_list").argumentsSchema()));
    }

    @Test
    void toolsDelegateToTheCatalogAndReturnItsAnswer() {
        CapturingCatalog catalog = new CapturingCatalog();
        ShopTools tools = new ShopTools(catalog);

        SearchNearbyArgs search = new SearchNearbyArgs("火锅", 500, null);
        ShopDetailArgs detail = new ShopDetailArgs(1);
        VoucherListArgs vouchers = new VoucherListArgs(1);

        assertSame(catalog.searchResult, tools.searchNearby(search));
        assertSame(catalog.detailResult, tools.shopDetail(detail));
        assertSame(catalog.voucherResult, tools.voucherList(vouchers));
        assertSame(search, catalog.lastSearch, "the declaration holder must not copy or rebuild arguments");
        assertSame(detail, catalog.lastDetail);
        assertSame(vouchers, catalog.lastVoucher);
    }

    @Test
    void toolsRefuseNullArguments() {
        ShopTools tools = new ShopTools(new NoopCatalog());

        assertThrows(NullPointerException.class, () -> tools.searchNearby(null));
        assertThrows(NullPointerException.class, () -> tools.shopDetail(null));
        assertThrows(NullPointerException.class, () -> tools.voucherList(null));
        assertThrows(NullPointerException.class, () -> new ShopTools(null));
    }

    @Test
    void theRegistryDoesNotLeakToolNamesAcrossInstances() {
        ToolRegistry first = registry(new NoopCatalog());
        ToolRegistry second = new ToolRegistry();

        assertEquals(3, first.size());
        assertEquals(0, second.size());
        assertTrue(second.definitions().isEmpty());
    }

    private static void assertNoWriteVerbs(String payload, String what) {
        var matcher = WRITE_VERBS.matcher(payload);
        List<String> hits = new ArrayList<>();
        while (matcher.find()) {
            hits.add(matcher.group());
        }
        assertTrue(hits.isEmpty(), what + " must not mention a write operation, but contains " + hits);
    }

    private static List<String> propertyNames(JsonNode schema) {
        List<String> names = new ArrayList<>();
        schema.get("properties").fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> requiredNames(JsonNode schema) {
        List<String> names = new ArrayList<>();
        schema.get("required").forEach(node -> names.add(node.asText()));
        return names;
    }

    /** Canned answers plus the exact instances it was handed. */
    static final class CapturingCatalog implements ShopCatalog {

        final List<ShopBrief> searchResult = List.of(new ShopBrief("1", "老王火锅", "火锅", 60, 4.5));
        final ShopDetail detailResult = new ShopDetail("1", "老王火锅", "火锅", "望江路 1 号", 60, 4.5);
        final List<VoucherBrief> voucherResult = List.of(new VoucherBrief("9", "1", "双人套餐", 8800, 12000));

        SearchNearbyArgs lastSearch;
        ShopDetailArgs lastDetail;
        VoucherListArgs lastVoucher;

        @Override
        public List<ShopBrief> searchNearby(SearchNearbyArgs args) {
            lastSearch = Objects.requireNonNull(args, "args");
            return searchResult;
        }

        @Override
        public ShopDetail shopDetail(ShopDetailArgs args) {
            lastDetail = Objects.requireNonNull(args, "args");
            return detailResult;
        }

        @Override
        public List<VoucherBrief> voucherList(VoucherListArgs args) {
            lastVoucher = Objects.requireNonNull(args, "args");
            return voucherResult;
        }
    }

    /** Returns empty-ish but valid data, so schema tests never need a real catalog. */
    static final class NoopCatalog implements ShopCatalog {

        @Override
        public List<ShopBrief> searchNearby(SearchNearbyArgs args) {
            return List.of();
        }

        @Override
        public ShopDetail shopDetail(ShopDetailArgs args) {
            return new ShopDetail("1", "老王火锅", "火锅", "望江路 1 号", 60, 4.5);
        }

        @Override
        public List<VoucherBrief> voucherList(VoucherListArgs args) {
            return List.of();
        }
    }
}
