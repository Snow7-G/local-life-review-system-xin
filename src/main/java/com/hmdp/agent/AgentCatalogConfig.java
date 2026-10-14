package com.hmdp.agent;

import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.IVoucherService;
import dev.lifeagent.tool.ShopCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the application's shop services into the agent-core {@link ShopCatalog} contract.
 *
 * <p>This is the only Spring bean the adapter package adds. It exposes the catalog as a plain
 * interface bean so a later wiring layer (W2-5) can hand it to
 * {@code new ShopTools(catalog)}; it is not a controller and registers no route.</p>
 */
@Configuration
public class AgentCatalogConfig {

    @Bean
    public SearchOrigin searchOrigin(
            @Value("${hmdp.agent.search-origin.x:}") String x,
            @Value("${hmdp.agent.search-origin.y:}") String y) {
        return new SearchOrigin(parse("hmdp.agent.search-origin.x", x), parse("hmdp.agent.search-origin.y", y));
    }

    @Bean
    public ShopCatalog shopCatalog(
            IShopService shopService,
            IVoucherService voucherService,
            IShopTypeService shopTypeService,
            SearchOrigin searchOrigin) {
        return new SpringShopCatalog(shopService, voucherService, shopTypeService, searchOrigin);
    }

    private static Double parse(String property, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(property + " 必须是数字，实际为：" + raw, e);
        }
    }
}
