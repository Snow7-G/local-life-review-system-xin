package com.hmdp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.IVoucherService;
import dev.lifeagent.tool.Permission;
import dev.lifeagent.tool.ShopTools;
import dev.lifeagent.tool.ToolDefinition;
import dev.lifeagent.tool.ToolErrorKind;
import dev.lifeagent.tool.ToolFailure;
import dev.lifeagent.tool.ToolOutcome;
import dev.lifeagent.tool.ToolRegistry;
import dev.lifeagent.tool.ToolSuccess;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end wiring check: the adapter is handed to agent-core's {@link ShopTools} and registered in
 * a {@link ToolRegistry}, exactly as the W2-5 wiring layer will do it.
 *
 * <p>This is also the proof that a service-side {@code Result.success == false} surfaces as a
 * model-visible {@link ToolFailure} rather than an exception that escapes, because the registry
 * turns the {@link ShopCatalogException} the adapter throws into an {@code EXECUTION_ERROR} outcome.</p>
 */
class ToolRegistryWiringTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SearchOrigin ORIGIN = new SearchOrigin(120.15, 30.31);

    private static ToolRegistry registry(SpringShopCatalog catalog) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerTools(new ShopTools(catalog));
        return registry;
    }

    private static SpringShopCatalog catalog(SpringShopCatalogTest.Fake<IShopService> shopService) {
        SpringShopCatalogTest.Fake<IShopTypeService> types = new SpringShopCatalogTest.Fake<>(IShopTypeService.class)
                .on("quaryById", Result.ok(Collections.singletonList(
                        new ShopType().setId(1L).setName("美食"))));
        SpringShopCatalogTest.Fake<IVoucherService> vouchers = new SpringShopCatalogTest.Fake<>(IVoucherService.class);
        return new SpringShopCatalog(
                shopService.service(), vouchers.service(), types.service(), ORIGIN);
    }

    @Test
    void registersTheThreeReadOnlyTools() {
        ToolRegistry registry = registry(catalog(new SpringShopCatalogTest.Fake<>(IShopService.class)));

        assertEquals(3, registry.size());
        for (ToolDefinition definition : registry.definitions()) {
            assertEquals(Permission.READ, definition.permission(), definition.name());
        }
    }

    @Test
    void searchNearbyRunsThroughTheRegistryAndReturnsTheFilteredShop() throws Exception {
        Shop near = new Shop().setId(1L).setName("近").setTypeId(1L)
                .setAvgPrice(60L).setScore(42).setDistance(100.0);
        SpringShopCatalogTest.Fake<IShopService> shopService = new SpringShopCatalogTest.Fake<>(IShopService.class)
                .on("quaryByShop", Result.ok(Collections.singletonList(near)));

        ToolOutcome outcome = registry(catalog(shopService))
                .invoke("search_nearby", MAPPER.readTree("{\"category\":\"美食\",\"radius\":500}"));

        ToolSuccess success = assertInstanceOf(ToolSuccess.class, outcome);
        JsonNode result = success.result();
        assertEquals(1, result.size());
        assertEquals("1", result.get(0).path("shopId").asText());
        assertEquals("美食", result.get(0).path("category").asText());
        assertEquals(4.2, result.get(0).path("score").asDouble(), 1e-9);
    }

    @Test
    void serviceFailureBecomesAModelVisibleToolError() throws Exception {
        SpringShopCatalogTest.Fake<IShopService> shopService = new SpringShopCatalogTest.Fake<>(IShopService.class)
                .on("quaryByShop", Result.fail("查询参数不正确"));

        ToolOutcome outcome = registry(catalog(shopService))
                .invoke("search_nearby", MAPPER.readTree("{\"category\":\"美食\",\"radius\":500}"));

        ToolFailure failure = assertInstanceOf(ToolFailure.class, outcome);
        assertEquals(ToolErrorKind.EXECUTION_ERROR, failure.errorKind());
        assertTrue(failure.message().contains("查询参数不正确"), failure.message());
    }

    @Test
    void outOfRangeRadiusNeverReachesTheBusinessService() throws Exception {
        // radius 99999 breaks SearchNearbyArgs' own bound, so the registry must reject it before the
        // adapter - an unstubbed Fake would otherwise raise on any service call.
        SpringShopCatalogTest.Fake<IShopService> shopService = new SpringShopCatalogTest.Fake<>(IShopService.class);

        ToolOutcome outcome = registry(catalog(shopService))
                .invoke("search_nearby", MAPPER.readTree("{\"category\":\"美食\",\"radius\":99999}"));

        ToolFailure failure = assertInstanceOf(ToolFailure.class, outcome);
        assertEquals(ToolErrorKind.INVALID_ARGUMENTS, failure.errorKind());
        assertTrue(shopService.calls().isEmpty(), "参数非法时业务方法不该被调用");
    }
}
