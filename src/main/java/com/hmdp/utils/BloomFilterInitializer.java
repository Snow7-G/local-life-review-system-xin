package com.hmdp.utils;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;

@Slf4j
@Component
public class BloomFilterInitializer {




        @Autowired
        private IShopService shopService;

        // 布隆过滤器：存储所有存在的商铺ID
        private static BloomFilter<Long> bloomFilter;

        // 预期插入数量（根据实际商铺数量调整，黑马点评大概几十个，设1000足够）
        private static final int EXPECTED_INSERTIONS = 1000;
        // 误判率（1%即可，面试时可以说“通过调整误判率来平衡内存和过滤效果”）
        private static final double FPP = 0.01;

        @PostConstruct
        public void init() {
            log.info("开始初始化布隆过滤器...");

            // 1. 从数据库查询所有商铺ID
            List<Shop> shopList = shopService.list();

            // 2. 创建布隆过滤器
            bloomFilter = BloomFilter.create(
                    Funnels.longFunnel(),   // 存储Long类型的ID
                    EXPECTED_INSERTIONS,    // 预期元素数量
                    FPP                     // 误判率
            );

            // 3. 将所有商铺ID添加到布隆过滤器
            for (Shop shop : shopList) {
                bloomFilter.put(shop.getId());
            }

            log.info("布隆过滤器初始化完成，已加载 {} 个商铺ID", shopList.size());
        }

        /**
         * 判断商铺ID是否可能存在
         * @param id 商铺ID
         * @return true-可能存在，false-一定不存在
         */
        public static boolean mightExist(Long id) {
            if (bloomFilter == null) {
                log.warn("布隆过滤器未初始化");
                return true;  // 未初始化时放行，避免影响业务
            }
            return bloomFilter.mightContain(id);
        }
}
