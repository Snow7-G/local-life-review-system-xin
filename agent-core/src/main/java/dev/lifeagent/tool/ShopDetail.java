package dev.lifeagent.tool;

/**
 * One shop in full.
 *
 * @param shopId   the shop id, as text
 * @param name     the shop name
 * @param category the category, e.g. 火锅
 * @param address  the street address
 * @param avgPrice average spend per person in yuan; may be unknown
 * @param score    rating; may be unknown
 */
public record ShopDetail(
        String shopId,
        String name,
        String category,
        String address,
        Integer avgPrice,
        Double score) {
}
