package com.hmdp.agent;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.IVoucherService;
import dev.lifeagent.tool.SearchNearbyArgs;
import dev.lifeagent.tool.ShopBrief;
import dev.lifeagent.tool.ShopDetail;
import dev.lifeagent.tool.ShopDetailArgs;
import dev.lifeagent.tool.VoucherBrief;
import dev.lifeagent.tool.VoucherListArgs;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the adapter against fake services: no Spring context, no Redis, no sockets.
 *
 * <p>The fakes are hand-written {@link Proxy} stubs rather than Mockito mocks on purpose - Mockito's
 * inline mock maker self-attaches through a socket, which this sandbox forbids. Each case maps to a
 * requirement in {@code docs/tasks/W2-4-spring-adapter.md}; the self review
 * {@code docs/W2-4-SPRING-ADAPTER.md} points at the method that proves each one.</p>
 */
class SpringShopCatalogTest {

    private static final SearchOrigin ORIGIN = new SearchOrigin(120.15, 30.31);

    /** A stub that only answers the reads it was told to, and records every call for the read-only checks. */
    static final class Fake<T> {
        private final Map<String, Object> answers = new HashMap<>();
        private final List<String> calls = new ArrayList<>();
        private final T service;

        @SuppressWarnings("unchecked")
        Fake(Class<T> type) {
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return type.getSimpleName() + "Fake";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == args[0];
                }
                calls.add(name);
                if (!answers.containsKey(name)) {
                    // Unstubbed includes every write method: calling one fails the test loudly.
                    throw new AssertionError("只读适配器触发了未预期的调用：" + method);
                }
                Object answer = answers.get(name);
                if (answer instanceof RuntimeException failure) {
                    throw failure;
                }
                return answer;
            };
            this.service = (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
        }

        Fake<T> on(String methodName, Object answer) {
            answers.put(methodName, answer);
            return this;
        }

        T service() {
            return service;
        }

        List<String> calls() {
            return calls;
        }
    }

    private static Fake<IShopService> shops(Object quaryByShop) {
        return new Fake<>(IShopService.class).on("quaryByShop", quaryByShop);
    }

    private static Fake<IVoucherService> vouchers(Object queryVoucherOfShop) {
        return new Fake<>(IVoucherService.class).on("queryVoucherOfShop", queryVoucherOfShop);
    }

    private static Fake<IShopTypeService> types(ShopType... all) {
        return new Fake<>(IShopTypeService.class).on("quaryById", Result.ok(Arrays.asList(all)));
    }

    private static ShopType type(long id, String name) {
        return new ShopType().setId(id).setName(name);
    }

    private static Shop shop(long id, String name, long typeId, Long avgPrice, Integer score, Double distance) {
        return new Shop()
                .setId(id)
                .setName(name)
                .setTypeId(typeId)
                .setAvgPrice(avgPrice)
                .setScore(score)
                .setDistance(distance);
    }

    private static SpringShopCatalog catalog(Fake<IShopService> shopService, Fake<IVoucherService> voucherService,
                                             Fake<IShopTypeService> shopTypeService) {
        return new SpringShopCatalog(
                shopService.service(), voucherService.service(), shopTypeService.service(), ORIGIN);
    }

    private static SearchNearbyArgs nearby(String category, int radius, Integer maxPrice) {
        return new SearchNearbyArgs(category, radius, maxPrice);
    }

    private static ShopCatalogException expectError(Executable call) {
        return assertThrows(ShopCatalogException.class, call::run);
    }

    @FunctionalInterface
    private interface Executable {
        void run();
    }

    private static void assertNoWrites(Fake<?>... fakes) {
        for (Fake<?> fake : fakes) {
            for (String call : fake.calls()) {
                assertFalse(looksLikeWrite(call), () -> "只读适配器调用了写方法：" + call);
            }
        }
    }

    private static boolean looksLikeWrite(String name) {
        return name.startsWith("save") || name.startsWith("update") || name.startsWith("remove")
                || name.startsWith("insert") || name.startsWith("delete") || name.startsWith("add")
                || name.startsWith("put") || name.startsWith("set");
    }

    @Nested
    class SearchNearby {

        @Test
        void radiusFilterKeepsOnlyShopsWithinTheRequestedDistance() {
            Shop near = shop(1, "近", 1, 60L, 42, 100.0);
            Shop mid = shop(2, "中", 1, 60L, 42, 800.0);
            Shop far = shop(3, "远", 1, 60L, 42, 2000.0);
            Fake<IShopService> shopService = shops(Result.ok(Arrays.asList(near, mid, far)));
            Fake<IShopTypeService> shopTypeService = types(type(1, "美食"));

            List<ShopBrief> results = catalog(shopService, vouchers(null), shopTypeService)
                    .searchNearby(nearby("美食", 500, null));

            assertEquals(1, results.size());
            ShopBrief only = results.get(0);
            assertEquals("1", only.shopId());
            assertEquals("近", only.name());
            assertEquals("美食", only.category());
            assertEquals(Integer.valueOf(60), only.avgPrice());
            assertEquals(4.2, only.score(), 1e-9);
            assertNoWrites(shopService, shopTypeService);
        }

        @Test
        void budgetFilterExcludesTooExpensiveShopsAndIsSkippedWhenNull() {
            Shop cheap = shop(1, "便宜", 1, 50L, 40, 10.0);
            Shop pricey = shop(2, "贵", 1, 90L, 40, 20.0);
            Shop exactly = shop(3, "刚好", 1, 80L, 40, 30.0);
            Shop unknown = shop(4, "无价", 1, null, 40, 40.0);
            Fake<IShopService> shopService =
                    shops(Result.ok(Arrays.asList(cheap, pricey, exactly, unknown)));
            SpringShopCatalog catalog = catalog(shopService, vouchers(null), types(type(1, "美食")));

            List<ShopBrief> capped = catalog.searchNearby(nearby("美食", 5000, 80));

            assertEquals(Arrays.asList("1", "3"),
                    Arrays.asList(capped.get(0).shopId(), capped.get(1).shopId()));
            assertEquals(2, capped.size(), "90 超预算排除，恰好 80 保留，均价未知无法证明达标也排除");

            List<ShopBrief> uncapped = catalog.searchNearby(nearby("美食", 5000, null));

            assertEquals(4, uncapped.size(), "maxPrice 为 null 时不过滤预算");
            assertNoWrites(shopService);
        }

        @Test
        void unknownCategoryReturnsEmptyListWithoutGuessingAnId() {
            Fake<IShopService> shopService = shops(new AssertionError("解析失败时不该查店铺"));
            SpringShopCatalog catalog = catalog(shopService, vouchers(null), types(type(1, "美食")));

            List<ShopBrief> results = catalog.searchNearby(nearby("甜点", 500, null));

            assertTrue(results.isEmpty());
            assertNoWrites(shopService);
        }

        @Test
        void failedResultBecomesAnExplicitError() {
            Fake<IShopService> shopService = shops(Result.fail("查询参数不正确"));

            ShopCatalogException error = expectError(() -> catalog(shopService, vouchers(null), types(type(1, "美食")))
                    .searchNearby(nearby("美食", 500, null)));

            assertTrue(error.getMessage().contains("查询参数不正确"), error.getMessage());
        }

        @Test
        void aNullResultPayloadIsReadAsNoRows() {
            Fake<IShopService> shopService = shops(Result.ok(null));

            List<ShopBrief> results = catalog(shopService, vouchers(null), types(type(1, "美食")))
                    .searchNearby(nearby("美食", 500, null));

            assertTrue(results.isEmpty());
        }

        @Test
        void outOfRangeAveragePriceSaturatesInsteadOfWrapping() {
            Shop huge = shop(1, "天价", 1, Long.MAX_VALUE, 40, 10.0);
            Shop tiny = shop(2, "负价", 1, Long.MIN_VALUE, 40, 20.0);
            Fake<IShopService> shopService = shops(Result.ok(Arrays.asList(huge, tiny)));

            List<ShopBrief> results = catalog(shopService, vouchers(null), types(type(1, "美食")))
                    .searchNearby(nearby("美食", 5000, null));

            assertEquals(Integer.valueOf(Integer.MAX_VALUE), results.get(0).avgPrice());
            assertEquals(Integer.valueOf(Integer.MIN_VALUE), results.get(1).avgPrice());
        }

        @Test
        void wrongResultPayloadTypeFailsInsteadOfReturningPartialData() {
            Fake<IShopService> shopService = shops(Result.ok(new Shop().setId(1L)));
            SpringShopCatalog catalog = catalog(shopService, vouchers(null), types(type(1, "美食")));

            ShopCatalogException notAList = expectError(() -> catalog.searchNearby(nearby("美食", 500, null)));
            assertTrue(notAList.getMessage().contains("返回类型不符"), notAList.getMessage());
        }

        @Test
        void wrongListElementTypeFailsInsteadOfSkippingTheBadRow() {
            Shop good = shop(1, "好店", 1, 60L, 40, 10.0);
            Fake<IShopService> shopService = shops(Result.ok(Arrays.asList(good, "不是店铺")));

            ShopCatalogException error = expectError(() -> catalog(shopService, vouchers(null), types(type(1, "美食")))
                    .searchNearby(nearby("美食", 500, null)));

            assertTrue(error.getMessage().contains("返回类型不符"), error.getMessage());
        }

        @Test
        void searchWithoutCoordinatesExcludesShopsTheServiceDidNotPlace() {
            Shop placed = shop(1, "有距离", 1, 60L, 40, 100.0);
            Shop unplaced = shop(2, "无距离", 1, 60L, 40, null);
            Fake<IShopService> shopService = shops(Result.ok(Arrays.asList(placed, unplaced)));

            List<ShopBrief> results = catalog(shopService, vouchers(null), types(type(1, "美食")))
                    .searchNearby(nearby("美食", 5000, null));

            assertEquals(1, results.size());
            assertEquals("1", results.get(0).shopId());
        }
    }

    @Nested
    class ShopDetailAndVouchers {

        @Test
        void shopDetailMapsEntityFieldsAndCategoryName() {
            Shop shop = shop(3, "星聚会KTV", 2, 88L, 46, null);
            shop.setAddress("杭行路666号");
            Fake<IShopService> shopService = new Fake<>(IShopService.class).on("quaryById", Result.ok(shop));
            Fake<IShopTypeService> shopTypeService = types(type(1, "美食"), type(2, "KTV"));

            ShopDetail detail = catalog(shopService, vouchers(null), shopTypeService)
                    .shopDetail(new ShopDetailArgs(3));

            assertEquals("3", detail.shopId());
            assertEquals("星聚会KTV", detail.name());
            assertEquals("KTV", detail.category());
            assertEquals("杭行路666号", detail.address());
            assertEquals(Integer.valueOf(88), detail.avgPrice());
            assertEquals(4.6, detail.score(), 1e-9);
            assertNoWrites(shopService, shopTypeService);
        }

        @Test
        void missingShopIsReportedAsNoDetail() {
            Fake<IShopService> shopService = new Fake<>(IShopService.class).on("quaryById", Result.ok(null));

            ShopDetail detail = catalog(shopService, vouchers(null), types(type(1, "美食")))
                    .shopDetail(new ShopDetailArgs(3));

            assertNull(detail);
        }

        @Test
        void voucherListMapsAndClampsVoucherValues() {
            Voucher normal = new Voucher()
                    .setId(7L).setShopId(3L).setTitle("双人套餐")
                    .setPayValue(68L).setActualValue(100L);
            Voucher huge = new Voucher()
                    .setId(8L).setShopId(3L).setTitle("天价券")
                    .setPayValue(Long.MAX_VALUE).setActualValue(100L);
            Fake<IVoucherService> voucherService = vouchers(Result.ok(Arrays.asList(normal, huge)));

            List<VoucherBrief> briefs = catalog(shops(null), voucherService, types(type(1, "美食")))
                    .voucherList(new VoucherListArgs(3));

            assertEquals(2, briefs.size());
            assertEquals("7", briefs.get(0).voucherId());
            assertEquals("3", briefs.get(0).shopId());
            assertEquals("双人套餐", briefs.get(0).title());
            assertEquals(Integer.valueOf(68), briefs.get(0).payValue());
            assertEquals(Integer.valueOf(100), briefs.get(0).actualValue());
            assertEquals(Integer.valueOf(Integer.MAX_VALUE), briefs.get(1).payValue());
            assertNoWrites(voucherService);
        }

        @Test
        void shopDetailFailsWhenResultIsNotAShop() {
            Fake<IShopService> shopService = new Fake<>(IShopService.class).on("quaryById", Result.ok("不是店铺"));

            ShopCatalogException error = expectError(() -> catalog(shopService, vouchers(null), types(type(1, "美食")))
                    .shopDetail(new ShopDetailArgs(3)));

            assertTrue(error.getMessage().contains("返回类型不符"), error.getMessage());
        }

        @Test
        void voucherListFailsWhenResultIsFalse() {
            Fake<IVoucherService> voucherService = vouchers(Result.fail("该商铺不存在"));

            ShopCatalogException error = expectError(() -> catalog(shops(null), voucherService, types(type(1, "美食")))
                    .voucherList(new VoucherListArgs(3)));

            assertTrue(error.getMessage().contains("该商铺不存在"), error.getMessage());
        }

        @Test
        void voucherListFailsWhenPayloadIsNotAVoucherList() {
            Fake<IVoucherService> voucherService = vouchers(Result.ok(Collections.singletonList("不是券")));

            ShopCatalogException error = expectError(() -> catalog(shops(null), voucherService, types(type(1, "美食")))
                    .voucherList(new VoucherListArgs(3)));

            assertTrue(error.getMessage().contains("返回类型不符"), error.getMessage());
        }
    }

    @Nested
    class ReadOnly {

        @Test
        void noHappyPathCallsAWriteMethod() {
            Shop shop = shop(1, "近", 1, 60L, 40, 100.0);
            Fake<IShopService> shopService = new Fake<>(IShopService.class)
                    .on("quaryByShop", Result.ok(Collections.singletonList(shop)))
                    .on("quaryById", Result.ok(shop));
            Fake<IVoucherService> voucherService = vouchers(Result.ok(Collections.emptyList()));
            Fake<IShopTypeService> shopTypeService = types(type(1, "美食"));
            SpringShopCatalog catalog = catalog(shopService, voucherService, shopTypeService);

            catalog.searchNearby(nearby("美食", 500, null));
            catalog.shopDetail(new ShopDetailArgs(1));
            catalog.voucherList(new VoucherListArgs(1));

            assertNoWrites(shopService, voucherService, shopTypeService);
            assertEquals(Collections.singletonList("quaryByShop"), onlyReads(shopService, "quaryByShop"));
        }

        @Test
        void errorPathsAlsoAvoidWrites() {
            Fake<IShopService> shopService = new Fake<>(IShopService.class)
                    .on("quaryByShop", Result.fail("boom"))
                    .on("quaryById", Result.ok("不是店铺"));
            Fake<IVoucherService> voucherService = vouchers(Result.fail("boom"));
            Fake<IShopTypeService> shopTypeService = types(type(1, "美食"));
            SpringShopCatalog catalog = catalog(shopService, voucherService, shopTypeService);

            expectError(() -> catalog.searchNearby(nearby("美食", 500, null)));
            expectError(() -> catalog.shopDetail(new ShopDetailArgs(1)));
            expectError(() -> catalog.voucherList(new VoucherListArgs(1)));

            assertNoWrites(shopService, voucherService, shopTypeService);
        }

        private static List<String> onlyReads(Fake<?> fake, String name) {
            return fake.calls().stream().filter(call -> call.equals(name)).toList();
        }
    }
}
