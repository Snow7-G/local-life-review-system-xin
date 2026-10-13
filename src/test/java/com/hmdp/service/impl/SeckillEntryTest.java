package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SeckillEntryTest {
    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    void voucherCannotBeReservedBeforeItsStartTime() {
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        ISeckillVoucherService seckillVouchers = mock(ISeckillVoucherService.class);
        IVoucherService vouchers = mock(IVoucherService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVouchers);
        ReflectionTestUtils.setField(service, "voucherService", vouchers);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        when(vouchers.getById(9L)).thenReturn(new Voucher().setId(9L).setStatus(1).setType(1));
        when(seckillVouchers.getById(9L)).thenReturn(new SeckillVoucher()
                .setVoucherId(9L).setStock(5)
                .setBeginTime(LocalDateTime.now().plusHours(1))
                .setEndTime(LocalDateTime.now().plusHours(2)));

        Result result = service.vouch(9L);

        assertFalse(result.getSuccess());
        assertTrue(result.getErrorMsg().contains("未开始"));
        verifyNoInteractions(redis);
    }
}
