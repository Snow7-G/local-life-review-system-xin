package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {
    //创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit){
        //存入数据库
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //存入数据库
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    //缓存穿透
    public <R,ID> R quaryWithPathThrough(String keyPreFix, ID id, Class<R> type, Function<ID,R> dbFallBack, Long time, TimeUnit unit) {
        //商品展示缓存
        String key = keyPreFix + id;
        // 1,通过id在redis查询是否存在
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2存在，直接返回
        if (StrUtil.isNotBlank(json)) {
            //将string转成shop对象传递，反序列化
            return JSONUtil.toBean(json, type);
        }
        //判断是否命中为空值，如是，直接返回
        if (json != null) {
            return null;
        }
        // 3,不存在，通过数据库查询
        R r = dbFallBack.apply(id);
        // 4，数据库查询到不存在，返回错误信息
        if (r == null) {
            //将空值存储到redis之后（缓存穿透）
            stringRedisTemplate.opsForValue().set(key, "", time, unit);
            return null;
        }
        // 5.存在，将查询到的数据存储到redis中,并设定超时时间
      this.set(key,r,time,unit);
        // 6，返回前端
        return r;
    }
    public <R,ID> R quaryByLogicExpire(String keyPreFix, ID id, Class<R> type, Function<ID,R> dbFallBack, Long time, TimeUnit unit){
        String key = keyPreFix + id;
        //通过id获取店铺信息
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否命中，未命中，返回空
        if(StrUtil.isBlank(json)){
            return null;
        }
        //命中，判断逻辑过期时间是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        //已过期，开始缓存重建
        //1，获取互斥锁
        String localkey = LOCK_SHOP_KEY + id;
        boolean lock = createLock(localkey);
        if(lock){
            try {
                //2，成功，开始新的线程
                CACHE_REBUILD_EXECUTOR.submit(()->{
                    //重建缓存
                    //查询数据库
                    R r1 = dbFallBack.apply(id);
                    //重建缓存
                    this.setWithLogicalExpire(key,r1,time,unit);
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                //释放锁
                deleteLock(localkey);
            }
        }

        //返回旧数据
        return r;
    }
    public  boolean createLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 30, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);

    }
    // 缓存击穿-释放锁
    public void deleteLock(String key){
        stringRedisTemplate.delete(key);
    }
}
