package dev.lifeagent.tool;

/**
 * Arguments of the {@code voucher_list} tool.
 *
 * @param shopId the shop whose vouchers are listed; must be positive
 */
public record VoucherListArgs(@Desc("店铺 id，来自搜索结果") long shopId) {

    public VoucherListArgs {
        if (shopId <= 0) {
            throw new IllegalArgumentException("shopId 必须为正数");
        }
    }
}
