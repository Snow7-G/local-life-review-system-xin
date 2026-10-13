package com.hmdp.service.impl;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedRecoveryTest {
    @Test
    void rebuildsOnlyPostsPublishedWhileFollowing() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:feedRecovery;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE tb_follow (user_id BIGINT, follow_user_id BIGINT, create_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_blog (id BIGINT, user_id BIGINT, create_time TIMESTAMP)");
        jdbc.update("INSERT INTO tb_follow VALUES (7, 8, TIMESTAMP '2026-01-01 00:00:00')");
        jdbc.update("INSERT INTO tb_blog VALUES (100, 8, TIMESTAMP '2025-12-31 00:00:00')");
        jdbc.update("INSERT INTO tb_blog VALUES (101, 8, TIMESTAMP '2026-01-02 00:00:00')");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        BlogServiceImpl service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);

        service.rebuildFeed(7L);

        verify(zset).add(org.mockito.ArgumentMatchers.eq("feed:7"), org.mockito.ArgumentMatchers.eq("101"), anyDouble());
        org.mockito.Mockito.verify(zset, org.mockito.Mockito.never()).add(
                org.mockito.ArgumentMatchers.eq("feed:7"), org.mockito.ArgumentMatchers.eq("100"), anyDouble());
    }
}
