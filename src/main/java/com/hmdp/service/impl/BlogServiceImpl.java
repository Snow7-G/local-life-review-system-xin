package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 */

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    private static final PolicyFactory BLOG_CONTENT_POLICY = new HtmlPolicyBuilder()
            .allowElements("br", "p", "b", "strong", "i", "em", "ul", "ol", "li")
            .toFactory();

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private BlogLikePersistenceService likePersistence;
    @Override
    public Result queryById(Long id) {
        //查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("该笔记不存在");
        }
        blog.setContent(sanitizeContent(blog.getContent()));
        //查询blog相关的对象
        queryUser(blog);
        //判断是否点赞该博客
        isLiked(blog);
        return Result.ok(blog);
    }

    private void isLiked(Blog blog) {
        //1，获取用户id
        //用户未登录，无序查询
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return;
        }
        Long userId = user.getId();
        blog.setIsLike(likePersistence.isLiked(blog.getId(), userId));
    }

    private void queryUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.isLiked(blog);
            this.queryUser(blog);
        });
        return Result.ok(records);
    }
//笔记点赞情况
    @Override
    public Result updateBlogLike(Long id) {
        Long userId = UserHolder.getUser().getId();
        try { likePersistence.toggle(id, userId); }
        catch (IllegalArgumentException missing) { return Result.fail("笔记不存在"); }
        return Result.ok();
    }
    //点赞排序
    @Override
    public Result queryLikes(Long id) {
        List<Long> ids = likePersistence.topLikers(id);
        if (ids.isEmpty()) return Result.ok(java.util.Collections.emptyList());
        //将id集合拼接成字符串
        String idStr = StrUtil.join(",", ids);
        //根据用户id查找出用户 in(5,1) order by filter(id,1,5)
        List<UserDTO> userDTOs = userService.query().in("id",ids)
                .last("ORDER BY FIELD ( id,"+ idStr + ")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOs);
    }
//实现推送笔记给粉丝
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        blog.setContent(sanitizeContent(blog.getContent()));

        // 保存探店笔记
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败");
        }
        //获得粉丝集合 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //将笔记保存到redis，key是登录用户id，value是粉丝id
        for (Follow follow : follows) {
            //获得粉丝id
            Long userId = follow.getUserId();
            String key = FEED_KEY + userId;
            //推送
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }
//粉丝查看收件箱笔记（滚蛋分页查询）
    @Override
    public Result quaryFollowBlog(Long max, Integer offset) {
        //获得登录用户id
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(key))) rebuildFeed(userId);
        //根据id查询收件箱的笔记id和时间戳
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //解析
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;//寻找最小时间戳
        int os = 1;//寻找有多少个重复的时间戳
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String blogId = typedTuple.getValue();//笔记的id
            ids.add(Long.valueOf(blogId));
            long time = typedTuple.getScore().longValue();//笔记的时间戳
            if(minTime ==  time){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }
        //根据id查询笔记
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids)
                .last("ORDER BY FIELD ( id," + idStr + ")").list();
        for (Blog blog : blogs) {
            //查询blog相关的对象
            queryUser(blog);
            //判断是否点赞该博客
            isLiked(blog);
        }
        //封装
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    void rebuildFeed(Long userId) {
        jdbc.query("SELECT DISTINCT b.id, b.create_time FROM tb_blog b " +
                "JOIN tb_follow f ON b.user_id = f.follow_user_id " +
                "WHERE f.user_id = ? AND b.create_time >= f.create_time",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> stringRedisTemplate.opsForZSet().add(
                        FEED_KEY + userId, String.valueOf(rs.getLong("id")),
                        rs.getTimestamp("create_time").getTime()), userId);
    }

    static String sanitizeContent(String content) {
        return content == null ? null : BLOG_CONTENT_POLICY.sanitize(content);
    }
}
