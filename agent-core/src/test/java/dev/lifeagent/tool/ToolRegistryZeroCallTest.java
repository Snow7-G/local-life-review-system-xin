package dev.lifeagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the card's core promise: when the arguments do not satisfy the generated schema, the
 * business method behind the tool is <strong>not called at all</strong> - the rejection happens in
 * the registry and is handed back as a tool error.
 *
 * <p>Three independent mechanisms are used, because any one of them alone could be fooled:</p>
 * <ol>
 *   <li>a counting catalog: valid calls must increment the counter (positive control), invalid calls
 *       must leave it untouched;</li>
 *   <li>positive controls in both directions, so a test that never reaches the catalog through a
 *       broken registry cannot pass silently;</li>
 *   <li>a poison catalog (JDK dynamic proxy) that throws {@link AssertionError} from every business
 *       method, so <em>any</em> entry into business code fails the test loudly.</li>
 * </ol>
 */
class ToolRegistryZeroCallTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Test
    void validCallsDoReachTheCatalogSoTheCountersAreNotDeadCode() {
        CountingCatalog catalog = new CountingCatalog();
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(catalog));

        assertInstanceOf(ToolSuccess.class, registry.invoke("search_nearby",
                NODES.objectNode().put("category", "火锅").put("radius", 500)));
        assertInstanceOf(ToolSuccess.class, registry.invoke("shop_detail", NODES.objectNode().put("shopId", 1)));
        assertInstanceOf(ToolSuccess.class, registry.invoke("voucher_list", NODES.objectNode().put("shopId", 1)));

        assertEquals(1, catalog.searchCalls.get());
        assertEquals(1, catalog.detailCalls.get());
        assertEquals(1, catalog.voucherCalls.get());
    }

    @Test
    void theCountingCatalogIsReachedWithTheBoundArguments() {
        CountingCatalog catalog = new CountingCatalog();
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(catalog));

        registry.invoke("search_nearby", NODES.objectNode().put("category", "KTV").put("radius", 800));

        assertEquals(1, catalog.searchCalls.get());
        assertEquals(new SearchNearbyArgs("KTV", 800, null), catalog.lastSearchArgs);
        assertEquals("KTV", catalog.lastSearchArgs.category());
        assertNull(catalog.lastSearchArgs.maxPrice(), "an omitted optional field must stay null");
    }

    @Test
    void invalidArgumentsNeverReachTheBusinessMethod() {
        CountingCatalog catalog = new CountingCatalog();
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(catalog));

        for (InvalidCall call : invalidCalls()) {
            ToolFailure failure = assertInstanceOf(ToolFailure.class, registry.invoke(call.tool(), call.arguments()),
                    call.describe());
            assertEquals(ToolErrorKind.INVALID_ARGUMENTS, failure.errorKind(), call.describe());
            assertTrue(failure.message().startsWith("参数校验失败："), failure.message());
        }

        assertEquals(0, catalog.searchCalls.get(), "searchNearby must never have been entered");
        assertEquals(0, catalog.detailCalls.get(), "shopDetail must never have been entered");
        assertEquals(0, catalog.voucherCalls.get(), "voucherList must never have been entered");
        assertNull(catalog.lastSearchArgs, "no arguments may have been bound");
        assertNull(catalog.lastDetailArgs, "no arguments may have been bound");
        assertNull(catalog.lastVoucherArgs, "no arguments may have been bound");
    }

    @Test
    void everyInvalidCallIsCheckedIndividuallyAgainstAFreshCatalog() {
        for (InvalidCall call : invalidCalls()) {
            CountingCatalog catalog = new CountingCatalog();
            ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(catalog));

            assertInstanceOf(ToolFailure.class, registry.invoke(call.tool(), call.arguments()), call.describe());

            assertEquals(0, catalog.calls(), call.describe() + " must not call the catalog");
            assertNull(catalog.lastSearchArgs, call.describe());
            assertNull(catalog.lastDetailArgs, call.describe());
            assertNull(catalog.lastVoucherArgs, call.describe());
        }
    }

    @Test
    void thePoisonCatalogIsLethalWhenBusinessCodeIsReached() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(poisonCatalog()));

        AssertionError error = assertThrows(AssertionError.class, () -> registry.invoke("search_nearby",
                NODES.objectNode().put("category", "火锅").put("radius", 500)));
        assertEquals("business method must not be called: searchNearby", error.getMessage());
    }

    @Test
    void invalidArgumentsDoNotTripThePoisonCatalog() {
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(poisonCatalog()));

        for (InvalidCall call : invalidCalls()) {
            ToolFailure failure = assertInstanceOf(ToolFailure.class, registry.invoke(call.tool(), call.arguments()),
                    call.describe() + " must be rejected by the registry, not by business code");
            assertEquals(ToolErrorKind.INVALID_ARGUMENTS, failure.errorKind(), call.describe());
        }
    }

    @Test
    void schemaFailuresAndBindingFailuresBothStopBeforeBusinessCode() {
        CountingCatalog catalog = new CountingCatalog();
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(catalog));

        // 1) rejected by the schema check (required field missing)
        assertInstanceOf(ToolFailure.class, registry.invoke("search_nearby", NODES.objectNode()));
        // 2) passes the schema, rejected while binding into the record (bound check inside the record)
        assertInstanceOf(ToolFailure.class, registry.invoke("search_nearby",
                NODES.objectNode().put("category", "火锅").put("radius", 9999)));
        // 3) rejected by the schema check (unknown field)
        assertInstanceOf(ToolFailure.class, registry.invoke("shop_detail",
                NODES.objectNode().put("shopId", 1).put("name", "老王")));

        assertEquals(0, catalog.calls(), "neither the schema check nor the binding step may reach business code");
    }

    private static List<InvalidCall> invalidCalls() {
        List<InvalidCall> calls = new ArrayList<>();
        // search_nearby
        calls.add(new InvalidCall("search_nearby", NODES.objectNode(), "empty arguments"));
        calls.add(new InvalidCall("search_nearby", NODES.objectNode().put("category", "火锅"), "radius missing"));
        calls.add(new InvalidCall("search_nearby", NODES.objectNode().put("radius", 500), "category missing"));
        calls.add(new InvalidCall("search_nearby", NODES.objectNode().put("category", "火锅").put("radius", "500"),
                "radius of the wrong type"));
        calls.add(new InvalidCall("search_nearby", NODES.objectNode().put("category", 1).put("radius", 500),
                "category of the wrong type"));
        calls.add(new InvalidCall("search_nearby",
                NODES.objectNode().put("category", "火锅").put("radius", 500).put("maxPrice", "便宜"),
                "maxPrice of the wrong type"));
        calls.add(new InvalidCall("search_nearby",
                NODES.objectNode().put("category", "火锅").put("radius", 500).put("budget", 100),
                "unknown field"));
        calls.add(new InvalidCall("search_nearby",
                NODES.objectNode().put("category", "火锅").put("radius", 500).putNull("category"),
                "duplicate/contradictory field"));
        calls.add(new InvalidCall("search_nearby", NODES.objectNode().put("category", " ").put("radius", 500),
                "blank category"));
        calls.add(new InvalidCall("search_nearby", NODES.objectNode().put("category", "火锅").put("radius", 0),
                "radius below the minimum"));
        calls.add(new InvalidCall("search_nearby", NODES.objectNode().put("category", "火锅").put("radius", 5001),
                "radius above the maximum"));
        calls.add(new InvalidCall("search_nearby", NODES.objectNode().put("category", "火锅").putNull("radius"),
                "null for a required field"));
        calls.add(new InvalidCall("search_nearby", NODES.arrayNode(), "arguments that are not an object"));
        // shop_detail
        calls.add(new InvalidCall("shop_detail", NODES.objectNode(), "shopId missing"));
        calls.add(new InvalidCall("shop_detail", NODES.objectNode().put("shopId", "1"), "shopId of the wrong type"));
        calls.add(new InvalidCall("shop_detail", NODES.objectNode().put("shopId", 0), "shopId not positive"));
        calls.add(new InvalidCall("shop_detail", NODES.objectNode().put("shopId", -3), "negative shopId"));
        calls.add(new InvalidCall("shop_detail", NODES.objectNode().put("shopId", 1).put("id", 1), "unknown field"));
        // voucher_list
        calls.add(new InvalidCall("voucher_list", NODES.objectNode(), "shopId missing"));
        calls.add(new InvalidCall("voucher_list", NODES.objectNode().put("shopId", 1.5), "non integral shopId"));
        calls.add(new InvalidCall("voucher_list", NODES.objectNode().put("shopId", 0), "shopId not positive"));
        return calls;
    }

    private static ShopCatalog poisonCatalog() {
        return (ShopCatalog) Proxy.newProxyInstance(
                ToolRegistryZeroCallTest.class.getClassLoader(),
                new Class<?>[] {ShopCatalog.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "toString" -> {
                            return "poison ShopCatalog";
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        default -> throw new AssertionError("business method must not be called: " + method.getName());
                    }
                });
    }

    private record InvalidCall(String tool, JsonNode arguments, String describe) {

        /** Human-readable assertion message: which tool was called with what. */
        @Override
        public String describe() {
            return tool + " with " + describe;
        }
    }

    /** Counts every entry into business code and remembers what the arguments were bound to. */
    static final class CountingCatalog implements ShopCatalog {

        final AtomicInteger searchCalls = new AtomicInteger();
        final AtomicInteger detailCalls = new AtomicInteger();
        final AtomicInteger voucherCalls = new AtomicInteger();

        volatile SearchNearbyArgs lastSearchArgs;
        volatile ShopDetailArgs lastDetailArgs;
        volatile VoucherListArgs lastVoucherArgs;

        int calls() {
            return searchCalls.get() + detailCalls.get() + voucherCalls.get();
        }

        @Override
        public List<ShopBrief> searchNearby(SearchNearbyArgs args) {
            searchCalls.incrementAndGet();
            lastSearchArgs = args;
            return List.of(new ShopBrief("1", "老王火锅", "火锅", 60, 4.5));
        }

        @Override
        public ShopDetail shopDetail(ShopDetailArgs args) {
            detailCalls.incrementAndGet();
            lastDetailArgs = args;
            return new ShopDetail("1", "老王火锅", "火锅", "望江路 1 号", 60, 4.5);
        }

        @Override
        public List<VoucherBrief> voucherList(VoucherListArgs args) {
            voucherCalls.incrementAndGet();
            lastVoucherArgs = args;
            return List.of(new VoucherBrief("9", "1", "双人套餐", 8800, 12000));
        }
    }
}
