package com.hmdp.service.impl;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LegacyBlogLikeImporterTest {
    @Test
    void importsOnceAndDoesNotResurrectRemovedLikes() throws Exception {
        try {
            Process check = new ProcessBuilder("redis-server", "--version").start();
            Assumptions.assumeTrue(check.waitFor() == 0);
        } catch (java.io.IOException e) {
            Assumptions.assumeTrue(false, "redis-server is not installed");
        }
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Path log = Files.createTempFile("hmdp-likes-redis-", ".log");
        Process process = new ProcessBuilder("redis-server", "--bind", "127.0.0.1", "--port", String.valueOf(port),
                "--save", "", "--appendonly", "no").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", port);
        try {
            factory.afterPropertiesSet();
            StringRedisTemplate redis = new StringRedisTemplate(factory);
            for (int i = 0; i < 50; i++) {
                try { redis.opsForValue().set("ready", "1"); break; }
                catch (Exception e) { Thread.sleep(100); }
            }
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:likeImport;MODE=MySQL;DB_CLOSE_DELAY=-1");
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE tb_blog_like (blog_id BIGINT, user_id BIGINT, create_time TIMESTAMP, PRIMARY KEY(blog_id,user_id))");
            redis.opsForZSet().add("blog:liked:11", "7", 1000);
            LegacyBlogLikeImporter importer = new LegacyBlogLikeImporter(redis, jdbc);

            importer.migrate();
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_like", Integer.class));
            assertFalse(Boolean.TRUE.equals(redis.hasKey("blog:liked:11")));
            jdbc.update("DELETE FROM tb_blog_like");
            importer.migrate();
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_like", Integer.class));
        } finally {
            factory.destroy();
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }
}
