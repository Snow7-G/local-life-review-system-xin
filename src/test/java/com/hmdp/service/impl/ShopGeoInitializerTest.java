package com.hmdp.service.impl;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopGeoInitializerTest {
    @Test
    void indexesShopCoordinatesFromDatabaseOnStartup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:shopGeo;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE tb_shop (id BIGINT, type_id BIGINT, x DOUBLE, y DOUBLE)");
        jdbc.update("INSERT INTO tb_shop VALUES (3, 2, 120.1, 30.2)");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        GeoOperations<String, String> geo = mock(GeoOperations.class);
        when(redis.opsForGeo()).thenReturn(geo);

        new ShopGeoInitializer(jdbc, redis).rebuild();

        verify(geo).add("shop:geo:2", new Point(120.1, 30.2), "3");
    }
}
