package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.datetime.joda.LocalDateTimeParser;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
//全局唯一id生成器
public class RedisIdMake {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final long BEGIN_TIMESTAMP = 1640995200;
    //序列号
    private static final long COUNT_BITS = 32;
    public long nextId(String preFix){
        //1，生成时间戳
        LocalDateTime nowTime = LocalDateTime.now();
        long nowTimeSecond = nowTime.toEpochSecond(ZoneOffset.UTC);
        long time = nowTimeSecond - BEGIN_TIMESTAMP;
        //2，生成序列号
        //2,1 获得今天时间的年月日
        String data = nowTime.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count  = stringRedisTemplate.opsForValue().increment("icr" + preFix + data);
        //3，拼接返回
        return time << COUNT_BITS | count;
    }


}
