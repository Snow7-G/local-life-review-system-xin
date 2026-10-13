package com.hmdp.service.impl;

import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.entity.UserInfo;
import com.hmdp.mapper.UserInfoMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = UserInfoInsertTest.Config.class)
class UserInfoInsertTest {
    @Autowired UserInfoMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void insertsProfileWithCurrentUserId() {
        jdbc.execute("CREATE TABLE tb_user_info (user_id BIGINT PRIMARY KEY, introduce VARCHAR(128))");

        mapper.insert(new UserInfo().setUserId(7L).setIntroduce("个人介绍"));

        assertEquals("个人介绍", jdbc.queryForObject(
                "SELECT introduce FROM tb_user_info WHERE user_id = 7", String.class));
    }

    @Configuration
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:userInfoInsert;MODE=MySQL;DB_CLOSE_DELAY=-1");
            return source;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            SqlSessionFactory sessionFactory = factory.getObject();
            sessionFactory.getConfiguration().addMapper(UserInfoMapper.class);
            return sessionFactory;
        }
        @Bean UserInfoMapper mapper(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory).getMapper(UserInfoMapper.class);
        }
    }
}
