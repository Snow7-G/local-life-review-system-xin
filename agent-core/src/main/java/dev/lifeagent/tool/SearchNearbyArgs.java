package dev.lifeagent.tool;

/**
 * Arguments of the {@code search_nearby} tool.
 *
 * <p>The compact constructor enforces the same bound the schema's description states ("半径，单位米，
 * 最大 5000"). It runs while the arguments are bound, i.e. <em>before</em> the tool body, so an
 * out-of-range radius fails the call instead of reaching the catalog.</p>
 *
 * @param category the category to search for, e.g. 火锅、KTV
 * @param radius   search radius in meters, 1..{@value #MAX_RADIUS_METERS}
 * @param maxPrice optional average-price ceiling in yuan; {@code null} means no ceiling
 */
public record SearchNearbyArgs(
        @Desc("品类，比如火锅、KTV") String category,
        @Desc("半径，单位米，最大 5000") int radius,
        @Desc("人均预算上限，单位元，可不填") Integer maxPrice) {

    /** Largest radius the tool accepts, in meters. */
    public static final int MAX_RADIUS_METERS = 5_000;

    public SearchNearbyArgs {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category 不能为空");
        }
        if (radius < 1 || radius > MAX_RADIUS_METERS) {
            throw new IllegalArgumentException("radius 必须在 1.." + MAX_RADIUS_METERS + " 米之间");
        }
        if (maxPrice != null && maxPrice < 0) {
            throw new IllegalArgumentException("maxPrice 不能为负数");
        }
    }
}
