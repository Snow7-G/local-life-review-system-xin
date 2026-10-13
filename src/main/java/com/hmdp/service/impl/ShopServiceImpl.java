package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.BloomFilterInitializer;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.Response;
import org.apache.ibatis.javassist.bytecode.analysis.Executor;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
   //创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //商品展示缓存
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Override
    public Result quaryById(Long id) {
        //缓存穿透代码调用
        Shop shop = cacheClient.quaryWithPathThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
       //缓存击穿代码调用
        //Shop shop = cacheClient.quaryByLogicExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        // 6，返回前端
        return Result.ok(shop);
    }
    //缓存击穿-逻辑过期-封装逻辑过期时间
//    public void saveShop2(Long id,Long expiredTime) throws InterruptedException {
//        //1，查询店铺数据
//        Shop shop = getById(id);
//        Thread.sleep(200);
//        //2，封装逻辑过期时间
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(10));
//        //存入redis
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
//    }
    //缓存击穿-逻辑过期
//    public Shop quaryByLogicExpire(Long id){
//        String key = CACHE_SHOP_KEY + id;
//        //通过id获取店铺信息
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //判断是否命中，未命中，返回空
//        if(StrUtil.isBlank(shopJson)){
//            return null;
//        }
//        //命中，判断逻辑过期时间是否过期
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        if(expireTime.isAfter(LocalDateTime.now())){
//            return shop;
//        }
//        //已过期，开始缓存重建
//        //1，获取互斥锁
//        String localkey = LOCK_SHOP_KEY + id;
//        boolean lock = createLock(localkey);
//        if(lock){
//            try {
//                //2，成功，开始新的线程
//                CACHE_REBUILD_EXECUTOR.submit(()->{
//                    try {
//                        this.saveShop2(id,20L);
//                    } catch (InterruptedException e) {
//                        throw new RuntimeException(e);
//                    }finally {
////                //释放锁
////                deleteLock(localkey);
////            }
//                });
//            } catch (Exception e) {
//                throw new RuntimeException(e);
//            }
//        }
//        //返回旧数据
//        return shop;
//    }
//    //缓存击穿方法-互斥锁
//    public Shop quaryByM(Long id){
//        String keyLock = "lock:shop:"+ id;
//        Shop shop = null;
//        //缓存穿透代码
//        String key = CACHE_SHOP_KEY + id;
//        // 1,通过id在redis查询是否存在
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        try {
//            // 2存在，直接返回
//            if (StrUtil.isNotBlank(shopJson)) {
//                //将string转成shop对象传递，反序列化
//                 shop = JSONUtil.toBean(shopJson, Shop.class);
//                return shop;
//            }
//            //判断是否命中为空值，如是，直接返回
//            if (shopJson != null) {
//                return null;
//            }
//            // 3,不存在，尝试获取锁
//
//            boolean flag = createLock(keyLock);
//
//            if(!flag){
//                //5，获取失败，休眠，再次查询
//                Thread.sleep(30);
//                // 调用该方法
//                quaryByM(id);
//
//            }
//            //4 ，获取成功，再次查看redis缓存是否有数据
//            // 4-1,通过id在redis查询是否存在
//            String shopJson2 = stringRedisTemplate.opsForValue().get(key);
//            // 4-2存在，//redis存在数据，释放锁
//            if (StrUtil.isNotBlank(shopJson2)) {
//                //将string转成shop对象传递，反序列化
//                 shop = JSONUtil.toBean(shopJson2, Shop.class);
//                return shop;
//            }
//            //判断是否命中为空值，如是，直接返回
//            if (shopJson2 != null) {
//                return null;
//            }
//            //6，redis不存在数据，去数据库查询数据
//            shop = getById(id);
//            // 4，数据库查询到不存在，返回错误信息
//            if (shop == null) {
//                //将空值存储到redis之后（缓存穿透）
//                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//            // 5.存在，将查询到的数据存储到redis中,并设定超时时间
//            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            //查询完之后将数据返回并释放锁
//            deleteLock(keyLock);
//
//        }
//        // 6，返回前端
//        return shop;
//    }
////缓存击穿-创建锁
//    public  boolean createLock(String key){
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 30, TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag);
//
//    }
//// 缓存击穿-释放锁
//    public void deleteLock(String key){
//        stringRedisTemplate.delete(key);
//    }

    //写数据库操作+删除redis缓存
    @Transactional
    @Override
    public void update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            Result.fail("商铺id不存在");
        }
        //更新数据库
        updateById(shop);
        log.info("更新数据库已完成");
        //删除redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        log.info("删除数据库已完成");
    }
//根据距离查询店铺信息
    @Override
    public Result quaryByShop(Integer typeId, Integer current, Double x, Double y, String sortBy) {
        if (typeId == null || typeId <= 0 || current == null || current <= 0
                || sortBy == null || !"".equals(sortBy) && !"comments".equals(sortBy) && !"score".equals(sortBy)
                || x != null && (!Double.isFinite(x) || x < -180 || x > 180)
                || y != null && (!Double.isFinite(y) || y < -85.05112878 || y > 85.05112878)) {
            return Result.fail("查询参数不正确");
        }
        //1,判断是否根据距离查询
        if(x == null || y == null || !sortBy.isEmpty()
                || Boolean.FALSE.equals(stringRedisTemplate.hasKey(SHOP_GEO_KEY + typeId))){
            //否，则按照原本数据库查询
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .orderByDesc(!sortBy.isEmpty(), sortBy.isEmpty() ? "id" : sortBy)
                    .orderByAsc("id")
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        // 根据距离查询
        //补全分页参数from，end
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //从redis中查询，分页，结果为shopId,distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()  // GEOSEARCH key BYLONLAT x y RADIUS 5000 WITHDISTANCE
                .search(key, GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending().limit(end)
                );
        //解析出id
        if(results == null){
            return Result.ok(java.util.Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size() <= from){
            return Result.ok(java.util.Collections.emptyList());
        }
        //截取分页
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> map = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            //获取店铺id
            String shopStr = result.getContent().getName();
            ids.add(Long.valueOf(shopStr));
            //获取店铺距离
            Distance distance = result.getDistance();
            map.put(shopStr,distance);
        });
        //根据id查询店铺信息
        String idStr = StrUtil.join(",",ids);
        List<Shop> shops = query().eq("type_id", typeId).in("id", ids).last("ORDER BY FIELD (id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(map.get(shop.getId().toString()).getValue());
        }
        //返回商铺信息
        return Result.ok(shops);
    }

//    //缓存穿透代码实现
//    public Shop cachePen(Long id) {
//        //商品展示缓存
//        String key = CACHE_SHOP_KEY + id;
//        // 1,通过id在redis查询是否存在
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        // 2存在，直接返回
//        if (StrUtil.isNotBlank(shopJson)) {
//            //将string转成shop对象传递，反序列化
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        //判断是否命中为空值，如是，直接返回
//        if (shopJson != null) {
//            return null;
//        }
//        // 3,不存在，通过数据库查询
//        Shop shop = getById(id);
//        // 4，数据库查询到不存在，返回错误信息
//        if (shop == null) {
//            //将空值存储到redis之后（缓存穿透）
//            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        // 5.存在，将查询到的数据存储到redis中,并设定超时时间
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        // 6，返回前端
//        return shop;
//    }
}
