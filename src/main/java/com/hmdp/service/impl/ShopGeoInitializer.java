package com.hmdp.service.impl;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@Component
public class ShopGeoInitializer {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public ShopGeoInitializer(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rebuild() {
        jdbc.query("SELECT id, type_id, x, y FROM tb_shop", rs -> {
            double x = rs.getDouble("x");
            double y = rs.getDouble("y");
            if (x >= -180 && x <= 180 && y >= -85.05112878 && y <= 85.05112878) {
                redis.opsForGeo().add(SHOP_GEO_KEY + rs.getLong("type_id"),
                        new Point(x, y), String.valueOf(rs.getLong("id")));
            }
        });
    }
}
