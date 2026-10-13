package com.hmdp.service.impl;

import com.hmdp.dto.OrderStatusDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class SeckillOrderStatusTest {
    private VoucherOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        service = spy(new VoucherOrderServiceImpl());
    }

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
        service.stopConsumer();
    }

    @Test
    void anotherUsersOrderCannotBeRead() {
        doReturn(new VoucherOrder().setId(101L).setUserId(8L)).when(service).getById(101L);

        assertFalse(service.orderStatus(101L).getSuccess());
    }

    @Test
    void redisConfirmationWithoutDatabaseOrderIsNotSuccess() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        when(redis.opsForHash()).thenReturn(hashes);
        Map<Object, Object> request = new HashMap<>();
        request.put("userId", "7");
        request.put("status", "CONFIRMED");
        request.put("orderId", "101");
        when(hashes.entries("seckill:request:101")).thenReturn(request);
        doReturn(null).when(service).getById(101L);

        Result result = service.orderStatus(101L);

        assertEquals("DELAYED", ((OrderStatusDTO) result.getData()).getStatus());
    }
}
