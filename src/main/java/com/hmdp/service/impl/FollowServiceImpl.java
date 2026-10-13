package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private IUserService userService;
    @Resource
    private JdbcTemplate jdbc;
    //关注与取关
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1，获取登录用户id
        Long userId = UserHolder.getUser().getId();
        //2,判断是否关注
        if(isFollow){
            //2.1 关注，将tb_follow中user_id,follow_user_id添加进去
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            save(follow);
        }else{
            //2.1 未关注，删除
            remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));

        }
        return Result.ok();
    }
//是否关注
    @Override
    public Result isFollow(Long followUserId) {
        //1，获取登录用户id
        Long userId = UserHolder.getUser().getId();
        //2, 查看数据库是否有这个
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }
//实现共同关注
    @Override
    public Result commonFollow(Long followUserId) {
        //1，获得当前登录用户id
        Long userId = UserHolder.getUser().getId();
        List<Long> ids = jdbc.queryForList(
                "SELECT DISTINCT a.follow_user_id FROM tb_follow a JOIN tb_follow b " +
                        "ON a.follow_user_id = b.follow_user_id WHERE a.user_id = ? AND b.user_id = ?",
                Long.class, userId, followUserId);
        if(ids.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //通过id查询用户信息
        List<User> users = userService.listByIds(ids);
        List<Object> usersDTO = users.stream().map(user ->
            BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(usersDTO);
    }
}
