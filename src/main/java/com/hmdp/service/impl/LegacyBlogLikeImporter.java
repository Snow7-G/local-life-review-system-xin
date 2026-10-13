package com.hmdp.service.impl;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

@Component
public class LegacyBlogLikeImporter {
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public LegacyBlogLikeImporter(StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.redis = redis;
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void migrate() {
        jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_like", Long.class);
        List<String> keys = new ArrayList<>();
        try (RedisConnection connection = redis.getConnectionFactory().getConnection();
             Cursor<byte[]> scan = connection.scan(ScanOptions.scanOptions().match(BLOG_LIKED_KEY + "*").count(100).build())) {
            scan.forEachRemaining(key -> keys.add(new String(key, StandardCharsets.UTF_8)));
        }
        for (String key : keys) {
            long blogId = Long.parseLong(key.substring(BLOG_LIKED_KEY.length()));
            try (Cursor<ZSetOperations.TypedTuple<String>> members = redis.opsForZSet().scan(
                    key, ScanOptions.scanOptions().count(100).build())) {
                members.forEachRemaining(member -> jdbc.update(
                        "INSERT IGNORE INTO tb_blog_like (blog_id, user_id, create_time) VALUES (?, ?, ?)",
                        blogId, Long.parseLong(member.getValue()), new Timestamp(member.getScore().longValue())));
            }
            redis.delete(key);
        }
    }
}
