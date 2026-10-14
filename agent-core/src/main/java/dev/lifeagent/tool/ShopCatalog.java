package dev.lifeagent.tool;

import java.util.List;

/**
 * What the agent needs to know about shops, expressed independently of any business module.
 *
 * <p>agent-core must not depend on the application (手册 §3: agent-core 是一个和业务无关的模块), so
 * the tools in {@link ShopTools} talk to this interface instead of to a service class from the root
 * project. The implementation lives in the application and is wired in W2-3; agent-core only owns
 * the contract and the tool declarations.</p>
 *
 * <p>Implementations are read paths only: neither this interface nor the tools on top of it can
 * change server state, which is why all three tools are declared {@link Permission#READ}.</p>
 */
public interface ShopCatalog {

    /**
     * Finds shops near the user's current position.
     *
     * @param args category, radius and optional budget ceiling
     * @return matching shops; never {@code null}
     */
    List<ShopBrief> searchNearby(SearchNearbyArgs args);

    /**
     * Loads one shop's details.
     *
     * @param args the shop id
     * @return the shop
     */
    ShopDetail shopDetail(ShopDetailArgs args);

    /**
     * Lists the vouchers a shop currently offers.
     *
     * @param args the shop id
     * @return the shop's vouchers; never {@code null}
     */
    List<VoucherBrief> voucherList(VoucherListArgs args);
}
