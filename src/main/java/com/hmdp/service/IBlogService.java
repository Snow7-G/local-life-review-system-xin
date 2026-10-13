package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IBlogService extends IService<Blog> {

    Result queryById(Long id);

    Result queryHotBlog(Integer current);

    Result updateBlogLike(Long id);

    Result queryLikes(Long id);

    Result saveBlog(Blog blog);

    Result quaryFollowBlog(Long max, Integer offset);
}
