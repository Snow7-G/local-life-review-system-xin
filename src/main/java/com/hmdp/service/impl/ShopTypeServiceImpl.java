package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SHOP_LIST_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
//利用redis缓存存储商铺类型信息
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result quaryById() {
        //1，判断redis中是否存在该商铺类型
        String shopType = stringRedisTemplate.opsForValue().get(SHOP_LIST_KEY);
        //2，存在，直接返回前端
        if(StrUtil.isNotBlank(shopType)){
            List<ShopType> type = JSONUtil.toList(shopType, ShopType.class);
            return Result.ok(type);

        }
        //3,不存在，从数据库中查询
        List<ShopType> type = query().orderByAsc("sort").list();

        if(type == null){
            //5，没有查询的到，返回错误信息
            return Result.fail("该商铺不存在");
        }
        //4，查询的到，则存入redis
        stringRedisTemplate.opsForValue().set(SHOP_LIST_KEY,JSONUtil.toJsonStr(type));
        //6，将从数据库中查询到的返回前端
        return Result.ok(type);

    }
}
