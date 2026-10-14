package dev.lifeagent.tool;

import java.util.List;
import java.util.Objects;

/**
 * The three read-only shop tools the planner agent gets in W2.
 *
 * <p>This class is a declaration holder, not business logic: every method delegates to the injected
 * {@link ShopCatalog}. It exists so the wiring layer (W2-3) can register the tools with one call -
 * {@code registry.registerTools(new ShopTools(catalog))} - without agent-core knowing anything about
 * Spring or the application.</p>
 *
 * <p>All three tools are {@link Permission#READ}: they only look things up. Anything that changes
 * state (booking, ordering, refunding) is out of scope for this card and must not be added here
 * without a matching higher permission level.</p>
 */
public final class ShopTools {

    /** Tool name for "find shops nearby". */
    public static final String SEARCH_NEARBY = "search_nearby";

    /** Tool name for "load one shop's details". */
    public static final String SHOP_DETAIL = "shop_detail";

    /** Tool name for "list a shop's vouchers". */
    public static final String VOUCHER_LIST = "voucher_list";

    /** The tool names this class declares, in declaration order. */
    public static final List<String> READ_ONLY_TOOL_NAMES = List.of(SEARCH_NEARBY, SHOP_DETAIL, VOUCHER_LIST);

    /** Search nearby: the model must not call it when the user only asked about one known shop. */
    private static final String SEARCH_NEARBY_DESCRIPTION =
            "按位置和品类搜附近的店。用户提到距离、附近、周边时用；只问某家店的详情时别用";

    private static final String SHOP_DETAIL_DESCRIPTION =
            "查某家店的详情（地址、营业时间、人均、评分）。已经确定是哪家店、要看它的详细信息时用；"
                    + "还没确定店铺、或要搜周边时别用";

    private static final String VOUCHER_LIST_DESCRIPTION =
            "查某家店当前可领的优惠券/团购券。用户问这家店有没有优惠、能不能团购时用；还没确定店铺时别用";

    private final ShopCatalog catalog;

    /**
     * @param catalog the catalog the tools delegate to
     */
    public ShopTools(ShopCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * Finds shops near the user's current position.
     *
     * @param args category, radius and optional budget ceiling
     * @return matching shops
     */
    @AgentTool(name = SEARCH_NEARBY, permission = Permission.READ, description = SEARCH_NEARBY_DESCRIPTION)
    public List<ShopBrief> searchNearby(SearchNearbyArgs args) {
        Objects.requireNonNull(args, "args");
        return catalog.searchNearby(args);
    }

    /**
     * Loads one shop's details.
     *
     * @param args the shop id
     * @return the shop
     */
    @AgentTool(name = SHOP_DETAIL, permission = Permission.READ, description = SHOP_DETAIL_DESCRIPTION)
    public ShopDetail shopDetail(ShopDetailArgs args) {
        Objects.requireNonNull(args, "args");
        return catalog.shopDetail(args);
    }

    /**
     * Lists the vouchers a shop currently offers.
     *
     * @param args the shop id
     * @return the shop's vouchers
     */
    @AgentTool(name = VOUCHER_LIST, permission = Permission.READ, description = VOUCHER_LIST_DESCRIPTION)
    public List<VoucherBrief> voucherList(VoucherListArgs args) {
        Objects.requireNonNull(args, "args");
        return catalog.voucherList(args);
    }
}
