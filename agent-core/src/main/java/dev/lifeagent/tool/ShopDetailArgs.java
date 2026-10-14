package dev.lifeagent.tool;

/**
 * Arguments of the {@code shop_detail} tool.
 *
 * @param shopId the shop to load; must be positive
 */
public record ShopDetailArgs(@Desc("店铺 id，来自搜索结果") long shopId) {

    public ShopDetailArgs {
        if (shopId <= 0) {
            throw new IllegalArgumentException("shopId 必须为正数");
        }
    }
}
