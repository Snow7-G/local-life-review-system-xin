package com.hmdp.agent;

/**
 * The coordinate {@link SpringShopCatalog} searches around.
 *
 * <p>{@code search_nearby} carries a radius but no position (agent-core's {@code SearchNearbyArgs}
 * has no x/y field and must not be changed), while {@code IShopService.quaryByShop} can only fill
 * {@code Shop.distance} when it is given coordinates and takes the GEO branch. The origin therefore
 * has to come from the application side - it is supplied by {@link AgentCatalogConfig} and can be
 * overridden per environment without touching the contract.</p>
 */
public final class SearchOrigin {

    private final Double x;
    private final Double y;

    public SearchOrigin(Double x, Double y) {
        this.x = x;
        this.y = y;
    }

    public Double x() {
        return x;
    }

    public Double y() {
        return y;
    }
}
