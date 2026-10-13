package com.hmdp.utils;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

public class ReFreshIntecepor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;
    // 自动生成的构造方法
    public ReFreshIntecepor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //定义一个拦截器

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //  1，获取请求头中token
        String token = request.getHeader("authorization");

        if(token == null){
            return true;
        }
        //  2，获取请求头中token中的用户信息
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                .entries(LOGIN_USER_KEY + token);
        //3，判断用户是否存在
        if(userMap.isEmpty()){
           return true;
        }
        // 5，将用户信息usermap转化为user
        Object user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        //5,存在，将用户保存打破ThreadLocal中
        UserHolder.saveUser((UserDTO) user);
        // 6，重置token时间
        stringRedisTemplate.expire(LOGIN_USER_KEY + token,30L, TimeUnit.MINUTES);
        //6,放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }


}
