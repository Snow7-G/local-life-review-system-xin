package com.hmdp.service.impl;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BlogLikePersistenceService {
    private final JdbcTemplate jdbc;

    public BlogLikePersistenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isLiked(Long blogId, Long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_like WHERE blog_id = ? AND user_id = ?",
                Integer.class, blogId, userId);
        return count != null && count > 0;
    }

    public List<Long> topLikers(Long blogId) {
        return jdbc.queryForList("SELECT user_id FROM tb_blog_like WHERE blog_id = ? " +
                "ORDER BY create_time ASC, user_id ASC LIMIT 5", Long.class, blogId);
    }

    @Transactional
    public boolean toggle(Long blogId, Long userId) {
        List<Long> blog = jdbc.queryForList("SELECT id FROM tb_blog WHERE id = ? FOR UPDATE", Long.class, blogId);
        if (blog.isEmpty()) throw new IllegalArgumentException("笔记不存在");
        if (isLiked(blogId, userId)) {
            jdbc.update("DELETE FROM tb_blog_like WHERE blog_id = ? AND user_id = ?", blogId, userId);
            jdbc.update("UPDATE tb_blog SET liked = GREATEST(COALESCE(liked, 0) - 1, 0) WHERE id = ?", blogId);
            return false;
        }
        jdbc.update("INSERT INTO tb_blog_like (blog_id, user_id) VALUES (?, ?)", blogId, userId);
        jdbc.update("UPDATE tb_blog SET liked = COALESCE(liked, 0) + 1 WHERE id = ?", blogId);
        return true;
    }
}
