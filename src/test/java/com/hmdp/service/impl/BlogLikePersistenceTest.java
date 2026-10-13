package com.hmdp.service.impl;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = BlogLikePersistenceTest.Config.class)
class BlogLikePersistenceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired BlogLikePersistenceService likes;

    @BeforeEach
    void reset() {
        jdbc.execute("DROP TABLE IF EXISTS tb_blog_like");
        jdbc.execute("DROP TABLE IF EXISTS tb_blog");
        jdbc.execute("CREATE TABLE tb_blog (id BIGINT PRIMARY KEY, liked INT)");
        jdbc.execute("CREATE TABLE tb_blog_like (blog_id BIGINT, user_id BIGINT, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(blog_id,user_id))");
        jdbc.update("INSERT INTO tb_blog VALUES (11, 0)");
    }

    @Test
    void likeMembershipAndCounterSurviveRedisLoss() {
        assertTrue(likes.toggle(11L, 7L));
        assertTrue(likes.isLiked(11L, 7L));
        assertEquals(1, jdbc.queryForObject("SELECT liked FROM tb_blog WHERE id=11", Integer.class));
        assertEquals(7L, likes.topLikers(11L).get(0));
        assertFalse(likes.toggle(11L, 7L));
        assertFalse(likes.isLiked(11L, 7L));
        assertEquals(0, jdbc.queryForObject("SELECT liked FROM tb_blog WHERE id=11", Integer.class));
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:blogLikes;MODE=MySQL;DB_CLOSE_DELAY=-1");
            return source;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean BlogLikePersistenceService service(JdbcTemplate jdbc) { return new BlogLikePersistenceService(jdbc); }
    }
}
