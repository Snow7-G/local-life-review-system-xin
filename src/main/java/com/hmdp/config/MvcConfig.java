package com.hmdp.config;

import com.hmdp.utils.LoginIntercepor;
import com.hmdp.utils.ReFreshIntecepor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;
import java.lang.ref.PhantomReference;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //第一个拦截器，主要是刷新token有效期
        registry.addInterceptor(new ReFreshIntecepor(stringRedisTemplate)).order(0);
        //第二个拦截器，决定是否放行
        registry.addInterceptor(new LoginIntercepor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/**",
                        "/voucher/**"


                ).order(1);

    }
}
