package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdMake;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {
    private ExecutorService ex = Executors.newFixedThreadPool(300);
    @Resource
    private CacheClient cacheClient;
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisIdMake redisIdMake;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Test
    void Test(){
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L,shop,10L, TimeUnit.SECONDS);

    }
    @Test
    void TestId() throws InterruptedException {
        CountDownLatch downLatch = new CountDownLatch(300);
        Runnable task = ()->{
            for (int i = 0; i < 100; i++) {
                long id = redisIdMake.nextId("shop");
                System.out.println("id = " + id);
            }
            downLatch.countDown();
        };
        //开始时间
        long start = System.currentTimeMillis();
        for (int i = 0; i < 300 ; i++) {
            ex.submit(task);

        }
        downLatch.await();
      //结束时间
      long end = System.currentTimeMillis();
        System.out.println("用时为：" + (end - start));
    }

    @Test
    void loadShop(){
        //1，获取店铺信息
        List<Shop> list = shopService.list();
        //2,将店铺按照shopId分类,放到集合里面
        Map<Long,List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //将店铺添加到redis里面
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long TypeId = entry.getKey();
            List<Shop> shop = entry.getValue();
            String key = "shop:geo:" + TypeId;
            for (Shop shop1 : shop) {
                stringRedisTemplate.opsForGeo()
                        .add(key,new Point(shop1.getX(),shop1.getY()),shop1.getId().toString());
            }
        }
    }

}
