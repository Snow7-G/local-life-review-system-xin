package com.hmdp.agent.eval;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.IVoucherService;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The canned business data one evaluation task runs against, plus the hand-written service fakes.
 *
 * <p>A world is <em>data</em>, not a stub of the adapter: it is served through {@link Proxy} fakes of
 * the three application services to the real {@code SpringShopCatalog}, so category resolution, the
 * radius filter and the budget filter all still run in production code. No Spring, no MySQL, no
 * Redis, no socket - Mockito's inline mock maker self-attaches through a socket, which this sandbox
 * forbids, so the fakes are written out by hand exactly like the W2-6 fixtures do.</p>
 *
 * <p>The authoritative {@code distance} and {@code avgPrice} live here, which is what lets the judge
 * verify a radius or budget constraint from read-path data instead of from the model's prose.</p>
 */
final class EvalWorld {

    /** A shop row: the fields the read path consumes. */
    record EvalShop(long id, String name, String category, long typeId, long avgPrice, int score, double distance) {

        Shop toEntity() {
            return new Shop()
                    .setId(id)
                    .setName(name)
                    .setTypeId(typeId)
                    .setAddress(name + " 门店地址（示例市示例路 " + id + " 号）")
                    .setAvgPrice(avgPrice)
                    .setScore(score)
                    .setDistance(distance);
        }
    }

    /** A voucher row. */
    record EvalVoucher(long id, long shopId, String title, long payValue, long actualValue) {

        Voucher toEntity() {
            return new Voucher()
                    .setId(id)
                    .setShopId(shopId)
                    .setTitle(title)
                    .setPayValue(payValue)
                    .setActualValue(actualValue);
        }
    }

    /**
     * The category table every world serves.
     *
     * <p>{@code SpringShopCatalog.resolveTypeId} matches a category name exactly, so in this world
     * 美食 is its own leaf category (two shops) rather than the parent of 火锅 / 面馆 / 咖啡. That is
     * the honest behaviour of the frozen adapter and every task is written against it.</p>
     */
    static final List<ShopType> TYPES = List.of(
            new ShopType().setId(1L).setName("美食"),
            new ShopType().setId(2L).setName("火锅"),
            new ShopType().setId(3L).setName("KTV"),
            new ShopType().setId(4L).setName("咖啡"),
            new ShopType().setId(5L).setName("面馆"));

    /** Eleven shops across five categories; distances span 120 m to 4800 m, prices 20 to 200. */
    static final List<EvalShop> SHOPS = List.of(
            new EvalShop(101, "顺时针火锅", "火锅", 2, 88, 46, 120),
            new EvalShop(102, "老码头火锅", "火锅", 2, 150, 45, 640),
            new EvalShop(103, "蜀香火锅", "火锅", 2, 45, 43, 2100),
            new EvalShop(104, "海底捞", "火锅", 2, 200, 48, 4800),
            new EvalShop(201, "麦乐迪KTV", "KTV", 3, 120, 42, 300),
            new EvalShop(202, "星聚会KTV", "KTV", 3, 60, 41, 1500),
            new EvalShop(301, "Manner咖啡", "咖啡", 4, 25, 44, 250),
            new EvalShop(302, "BlueBottle咖啡", "咖啡", 4, 45, 47, 900),
            new EvalShop(401, "陈记面馆", "面馆", 5, 20, 45, 180),
            new EvalShop(501, "食事处日料", "美食", 1, 95, 46, 400),
            new EvalShop(502, "小面江湖", "美食", 1, 35, 42, 850));

    /** Vouchers keyed by shop; a shop with no entry has none. */
    static final List<EvalVoucher> VOUCHERS = List.of(
            new EvalVoucher(9001, 101, "88元代100元券", 88, 100),
            new EvalVoucher(9002, 101, "45元代50元券", 45, 50),
            new EvalVoucher(9003, 201, "1小时欢唱券", 39, 60),
            new EvalVoucher(9004, 102, "双人套餐券", 128, 168));

    /** The largest radius the frozen {@code SearchNearbyArgs} accepts. */
    static final int MAX_RADIUS_METERS = 5_000;

    /** Behaviour of {@code quaryByShop} (the GEO search) in a world. */
    enum SearchMode {
        /** Returns every shop of the requested category; the adapter filters. */
        NORMAL,
        /** Returns an empty list, i.e. nothing nearby. */
        EMPTY,
        /** Throws, standing in for a broken downstream. */
        FAILING
    }

    private final String name;
    private final SearchMode searchMode;
    private final List<EvalShop> shops = SHOPS;
    private final List<EvalVoucher> vouchers = VOUCHERS;

    private EvalWorld(String name, SearchMode searchMode) {
        this.name = name;
        this.searchMode = searchMode;
    }

    /** The normal world: eleven shops, distances 120 m .. 4800 m. */
    static EvalWorld main() {
        return new EvalWorld("main", SearchMode.NORMAL);
    }

    /** Nothing nearby: every search comes back empty. */
    static EvalWorld empty() {
        return new EvalWorld("empty", SearchMode.EMPTY);
    }

    /** The search backend always fails. */
    static EvalWorld broken() {
        return new EvalWorld("broken", SearchMode.FAILING);
    }

    /** @param reference the name a task set uses, e.g. {@code main} */
    static EvalWorld named(String reference) {
        return switch (reference) {
            case "main" -> main();
            case "empty" -> empty();
            case "broken" -> broken();
            default -> throw new IllegalArgumentException("未知的评测世界：" + reference);
        };
    }

    String name() {
        return name;
    }

    SearchMode searchMode() {
        return searchMode;
    }

    List<EvalShop> shops() {
        return shops;
    }

    List<EvalVoucher> vouchers() {
        return vouchers;
    }

    /** @return the shop with that id, or {@code null}. */
    EvalShop shop(long id) {
        for (EvalShop shop : shops) {
            if (shop.id() == id) {
                return shop;
            }
        }
        return null;
    }

    /** @return the shop whose name that text contains, or {@code null} when it names no shop. */
    EvalShop shopNamedIn(String text) {
        List<EvalShop> found = shopsNamedIn(text);
        return found.isEmpty() ? null : found.get(0);
    }

    /** @return every shop whose full name appears in {@code text}; the judge's only "was it presented". */
    List<EvalShop> shopsNamedIn(String text) {
        List<EvalShop> found = new ArrayList<>();
        if (text == null) {
            return found;
        }
        for (EvalShop shop : shops) {
            if (text.contains(shop.name())) {
                found.add(shop);
            }
        }
        return found;
    }

    /** @return the shop ids whose name appears in {@code text}. */
    List<Long> shopIdsNamedIn(String text) {
        List<Long> ids = new ArrayList<>();
        for (EvalShop shop : shopsNamedIn(text)) {
            ids.add(shop.id());
        }
        return ids;
    }

    /** @return the vouchers the read path would serve for that shop. */
    List<EvalVoucher> vouchersOf(long shopId) {
        List<EvalVoucher> found = new ArrayList<>();
        for (EvalVoucher voucher : vouchers) {
            if (voucher.shopId() == shopId) {
                found.add(voucher);
            }
        }
        return found;
    }

    /** @return every voucher title any shop offers; used to spot an invented voucher. */
    List<String> allVoucherTitles() {
        List<String> titles = new ArrayList<>();
        for (EvalVoucher voucher : vouchers) {
            titles.add(voucher.title());
        }
        return titles;
    }

    // ------------------------------------------------------------------ fakes

    /** The three application services, answering from this world and recording every call. */
    static final class Services {

        final Recording<IShopService> shop = new Recording<>(IShopService.class);
        final Recording<IVoucherService> voucher = new Recording<>(IVoucherService.class);
        final Recording<IShopTypeService> shopType = new Recording<>(IShopTypeService.class);

        Services(EvalWorld world) {
            shopType.on("quaryById", Result.ok(new ArrayList<>(TYPES)));
            shop.on("quaryByShop", (Answer) args -> switch (world.searchMode()) {
                case NORMAL -> Result.ok(shopsOfCategory(asLong(args[0])));
                case EMPTY -> Result.ok(List.of());
                case FAILING -> throw new IllegalStateException("下游数据库不可用");
            });
            shop.on("quaryById", (Answer) args -> {
                EvalShop found = world.shop(asLong(args[0]));
                return Result.ok(found == null ? null : found.toEntity());
            });
            voucher.on("queryVoucherOfShop", (Answer) args -> {
                List<Voucher> rows = new ArrayList<>();
                for (EvalVoucher each : world.vouchersOf(asLong(args[0]))) {
                    rows.add(each.toEntity());
                }
                return Result.ok(rows);
            });
        }

        private static List<Shop> shopsOfCategory(long typeId) {
            List<Shop> rows = new ArrayList<>();
            for (EvalShop shop : SHOPS) {
                if (shop.typeId() == typeId) {
                    rows.add(shop.toEntity());
                }
            }
            return rows;
        }

        private static long asLong(Object value) {
            return ((Number) value).longValue();
        }

        List<String> calls() {
            List<String> all = new ArrayList<>();
            all.addAll(shop.calls());
            all.addAll(voucher.calls());
            all.addAll(shopType.calls());
            return all;
        }

        /** Every method name that looks like a state change; read-only tasks must leave this empty. */
        List<String> writeCalls() {
            List<String> writes = new ArrayList<>();
            for (String call : calls()) {
                if (call.startsWith("save") || call.startsWith("update") || call.startsWith("remove")
                        || call.startsWith("insert") || call.startsWith("delete")
                        || call.startsWith("add") || call.startsWith("put")) {
                    writes.add(call);
                }
            }
            return writes;
        }
    }

    /** A hand-written recording fake; see the class comment for why it is not a Mockito mock. */
    static final class Recording<T> {

        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Object> answers = new HashMap<>();
        private final T service;

        @SuppressWarnings("unchecked")
        Recording(Class<T> type) {
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                switch (name) {
                    case "toString":
                        return type.getSimpleName() + "Recording";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        break;
                }
                calls.add(name);
                Object answer = answers.get(name);
                if (answer instanceof Answer dynamic) {
                    return dynamic.produce(args == null ? new Object[0] : args);
                }
                return answer;
            };
            this.service = (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
        }

        Recording<T> on(String methodName, Object answer) {
            answers.put(methodName, answer);
            return this;
        }

        T service() {
            return service;
        }

        List<String> calls() {
            return List.copyOf(calls);
        }
    }

    /** An answer computed from the call arguments. */
    @FunctionalInterface
    interface Answer {
        Object produce(Object[] args) throws Throwable;
    }
}
