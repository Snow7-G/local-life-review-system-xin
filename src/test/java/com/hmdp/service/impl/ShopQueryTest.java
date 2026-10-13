package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShopQueryTest {
    @Test
    void categoryWithoutCoordinatesReturnsDatabasePage() {
        ShopMapper mapper = mock(ShopMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ShopServiceImpl service = new ShopServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        Shop shop = new Shop().setId(3L);
        when(mapper.selectPage(any(IPage.class), any(Wrapper.class))).thenAnswer(invocation -> {
            IPage<Shop> page = invocation.getArgument(0);
            page.setRecords(Collections.singletonList(shop));
            return page;
        });

        Result result = service.quaryByShop(2, 1, null, null, "");

        assertEquals(Collections.singletonList(shop), result.getData());
        verifyNoInteractions(redis);
    }

    @Test
    void popularitySortUsesDatabaseColumnWhitelist() {
        ShopMapper mapper = mock(ShopMapper.class);
        ShopServiceImpl service = new ShopServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.selectPage(any(IPage.class), any(Wrapper.class))).thenAnswer(invocation -> {
            IPage<Shop> page = invocation.getArgument(0);
            Wrapper<Shop> query = invocation.getArgument(1);
            assertEquals(true, query.getSqlSegment().contains("comments DESC"));
            page.setRecords(Collections.emptyList());
            return page;
        });

        assertEquals(Collections.emptyList(), service.quaryByShop(2, 1, null, null, "comments").getData());
        assertEquals(false, service.quaryByShop(2, 1, null, null, "comments;DROP TABLE tb_shop").getSuccess());
    }
}
