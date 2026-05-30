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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.sql.Wrapper;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    //关注与取关
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1，获取登录用户id
        Long userId = UserHolder.getUser().getId();
        String key = "folllows:" + userId;
        //2,判断是否关注
        if(isFollow){
            //2.1 关注，将tb_follow中user_id,follow_user_id添加进去
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean isSuccesee = save(follow);
            if(isSuccesee){
                //存入redis
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else{
            //2.1 未关注，删除
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if(isSuccess){
                //取出redis
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }

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
        //2，用户key和被关注用户key交集得到共同关注用户
        String key = "folllows:" + userId;
        String key2 = "folllows:" + followUserId;
        Set<String> usersId = stringRedisTemplate.opsForSet().intersect(key, key2);
        if(usersId == null || usersId.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //3，解析id
        List<Long> ids = usersId.stream().map(Long::valueOf).collect(Collectors.toList());
        //通过id查询用户信息
        List<User> users = userService.listByIds(ids);
        List<Object> usersDTO = users.stream().map(user ->
            BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(usersDTO);
    }
}
