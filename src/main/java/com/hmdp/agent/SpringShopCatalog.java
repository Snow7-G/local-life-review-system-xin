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
import dev.lifeagent.tool.ShopCatalog;
import dev.lifeagent.tool.ShopDetail;
import dev.lifeagent.tool.ShopDetailArgs;
import dev.lifeagent.tool.VoucherBrief;
import dev.lifeagent.tool.VoucherListArgs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapts the application's shop services to the agent-core {@link ShopCatalog} contract.
 *
 * <p>Every path here is a read: the class only calls query methods
 * ({@code quaryByShop}, {@code quaryById}, {@code queryVoucherOfShop} and the read side of
 * {@link IShopTypeService}) and never a write, so the three tools stay {@code Permission.READ}.
 * It is deliberately never injected into an existing controller and declares no web route.</p>
 *
 * <p>Two decisions are worth spelling out (both are recorded in {@code docs/W2-4-SPRING-ADAPTER.md}):</p>
 * <ul>
 *   <li>Radius and budget filtering happen here rather than in the service, because
 *       {@code quaryByShop(Integer, Integer, Double, Double, String)} accepts neither. The tool's
 *       {@code radius} is applied to the {@code Shop.distance} the service filled in, and
 *       {@code maxPrice} to {@code Shop.avgPrice}.</li>
 *   <li>{@code Result.success == false} is treated as a tool failure and raises
 *       {@link ShopCatalogException}; a {@code data} payload of the wrong type fails explicitly too,
 *       so a malformed service result never degrades into a silently truncated answer.</li>
 * </ul>
 */
public class SpringShopCatalog implements ShopCatalog {

    private final IShopService shopService;
    private final IVoucherService voucherService;
    private final IShopTypeService shopTypeService;
    private final SearchOrigin origin;

    public SpringShopCatalog(
            IShopService shopService,
            IVoucherService voucherService,
            IShopTypeService shopTypeService,
            SearchOrigin origin) {
        this.shopService = shopService;
        this.voucherService = voucherService;
        this.shopTypeService = shopTypeService;
        this.origin = origin;
    }

    @Override
    public List<ShopBrief> searchNearby(SearchNearbyArgs args) {
        Long typeId = resolveTypeId(args.category());
        if (typeId == null) {
            // Unknown category: no id to search with, and guessing one would be wrong. Empty, not an error.
            return Collections.emptyList();
        }

        Result result = shopService.quaryByShop(
                typeId.intValue(), 1, origin.x(), origin.y(), "");
        List<Shop> shops = requireList(result, Shop.class, "quaryByShop");

        Map<Long, String> typeNames = loadTypeNames();
        List<ShopBrief> briefs = new ArrayList<>();
        for (Shop shop : shops) {
            if (!withinRadius(shop, args.radius()) || !withinBudget(shop, args.maxPrice())) {
                continue;
            }
            briefs.add(new ShopBrief(
                    String.valueOf(shop.getId()),
                    shop.getName(),
                    typeNames.get(shop.getTypeId()),
                    ValueCoercions.clampToInt(shop.getAvgPrice()),
                    ValueCoercions.scoreFromTenths(shop.getScore())));
        }
        return briefs;
    }

    @Override
    public ShopDetail shopDetail(ShopDetailArgs args) {
        Result result = shopService.quaryById(args.shopId());
        Shop shop = requireOne(result, Shop.class, "quaryById");
        if (shop == null) {
            return null;
        }
        return new ShopDetail(
                String.valueOf(shop.getId()),
                shop.getName(),
                categoryNameOf(shop.getTypeId()),
                shop.getAddress(),
                ValueCoercions.clampToInt(shop.getAvgPrice()),
                ValueCoercions.scoreFromTenths(shop.getScore()));
    }

    @Override
    public List<VoucherBrief> voucherList(VoucherListArgs args) {
        Result result = voucherService.queryVoucherOfShop(args.shopId());
        List<Voucher> vouchers = requireList(result, Voucher.class, "queryVoucherOfShop");
        List<VoucherBrief> briefs = new ArrayList<>(vouchers.size());
        for (Voucher voucher : vouchers) {
            briefs.add(new VoucherBrief(
                    String.valueOf(voucher.getId()),
                    String.valueOf(voucher.getShopId()),
                    voucher.getTitle(),
                    ValueCoercions.clampToInt(voucher.getPayValue()),
                    ValueCoercions.clampToInt(voucher.getActualValue())));
        }
        return briefs;
    }

    private boolean withinRadius(Shop shop, int radius) {
        Double distance = shop.getDistance();
        if (distance == null) {
            // The service only fills distance on its GEO path; a shop with no distance cannot be proven
            // to satisfy the radius, so it is excluded rather than assumed near.
            return false;
        }
        return distance <= radius;
    }

    private boolean withinBudget(Shop shop, Integer maxPrice) {
        if (maxPrice == null) {
            return true;
        }
        Long avgPrice = shop.getAvgPrice();
        return avgPrice != null && avgPrice <= maxPrice;
    }

    private String categoryNameOf(Long typeId) {
        if (typeId == null) {
            return null;
        }
        String name = loadTypeNames().get(typeId);
        return name != null ? name : typeId.toString();
    }

    /** Resolves a category name such as 火锅 to its type id, or {@code null} when there is no match. */
    private Long resolveTypeId(String category) {
        String wanted = category.trim();
        for (ShopType type : loadTypes()) {
            if (wanted.equals(type.getName())) {
                return type.getId();
            }
        }
        return null;
    }

    private Map<Long, String> loadTypeNames() {
        Map<Long, String> names = new HashMap<>();
        for (ShopType type : loadTypes()) {
            names.put(type.getId(), type.getName());
        }
        return names;
    }

    private List<ShopType> loadTypes() {
        Result result = shopTypeService.quaryById();
        return requireList(result, ShopType.class, "shopTypeService.quaryById");
    }

    /**
     * Reads a {@code Result} whose payload must be a list of {@code elementType}.
     *
     * <p>{@code success == false} becomes a {@link ShopCatalogException}; a payload that is not a
     * list, or whose elements are not {@code elementType}, is reported as an explicit type mismatch
     * instead of being skipped or partially returned. A {@code null} payload is read as "no rows"
     * and returned as an empty list.</p>
     */
    private <T> List<T> requireList(Result result, Class<T> elementType, String source) {
        requireSuccess(result, source);
        Object data = result.getData();
        if (data == null) {
            return Collections.emptyList();
        }
        if (!(data instanceof List<?> raw)) {
            throw new ShopCatalogException(
                    source + " 返回类型不符：期望 List<" + elementType.getSimpleName()
                            + ">，实际为 " + data.getClass().getName());
        }
        List<T> typed = new ArrayList<>(raw.size());
        for (Object element : raw) {
            if (!elementType.isInstance(element)) {
                throw new ShopCatalogException(
                        source + " 返回类型不符：列表元素期望 " + elementType.getSimpleName()
                                + "，实际为 " + describe(element));
            }
            typed.add(elementType.cast(element));
        }
        return typed;
    }

    /**
     * Reads a {@code Result} whose payload is a single {@code elementType}, or {@code null} when the
     * service found nothing.
     */
    private <T> T requireOne(Result result, Class<T> elementType, String source) {
        requireSuccess(result, source);
        Object data = result.getData();
        if (data == null) {
            return null;
        }
        if (!elementType.isInstance(data)) {
            throw new ShopCatalogException(
                    source + " 返回类型不符：期望 " + elementType.getSimpleName()
                            + "，实际为 " + describe(data));
        }
        return elementType.cast(data);
    }

    private void requireSuccess(Result result, String source) {
        if (result == null) {
            throw new ShopCatalogException(source + " 返回 null");
        }
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            String reason = result.getErrorMsg() == null ? "未知错误" : result.getErrorMsg();
            throw new ShopCatalogException(source + " 失败：" + reason);
        }
    }

    private String describe(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
