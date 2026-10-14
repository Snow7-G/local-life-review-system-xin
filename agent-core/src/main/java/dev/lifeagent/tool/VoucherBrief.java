package dev.lifeagent.tool;

/**
 * One voucher a shop offers.
 *
 * @param voucherId   the voucher id, as text
 * @param shopId      the shop it belongs to, as text
 * @param title       the voucher title
 * @param payValue    what the user pays, in yuan; may be unknown
 * @param actualValue what the voucher is worth, in yuan; may be unknown
 */
public record VoucherBrief(
        String voucherId,
        String shopId,
        String title,
        Integer payValue,
        Integer actualValue) {
}
