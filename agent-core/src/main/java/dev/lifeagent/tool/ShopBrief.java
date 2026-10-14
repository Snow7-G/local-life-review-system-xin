package dev.lifeagent.tool;

/**
 * Short shop entry, as shown in a list of search results.
 *
 * @param shopId    the shop id, as text so it survives JSON round trips
 * @param name      the shop name
 * @param category  the category, e.g. 火锅
 * @param avgPrice  average spend per person in yuan; may be unknown
 * @param score     rating; may be unknown
 */
public record ShopBrief(String shopId, String name, String category, Integer avgPrice, Double score) {
}
