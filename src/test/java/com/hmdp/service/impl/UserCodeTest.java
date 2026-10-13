package com.hmdp.service.impl;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_LIMIT_TTL;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserCodeTest {
    @Test
    void repeatCodeRequestIsLimitedForSixtySeconds() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);
        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "demoPhone", "13800138000");

        service.setPhone("13800138000");

        verify(values).setIfAbsent("login:code:limit:13800138000", "1", LOGIN_CODE_LIMIT_TTL, TimeUnit.SECONDS);
    }

    @Test
    void ordinaryPhoneIsRejectedUntilSmsDeliveryIsConfigured() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);

        assertFalse(service.setPhone("13800138000").getSuccess());
        verifyNoInteractions(redis);
    }

    @Test
    void loginRejectsCodeAfterTooManyAttempts() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(-2L);
        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("13800138000");
        form.setCode("123456");

        assertTrue(service.login(form).getErrorMsg().contains("尝试次数"));
    }

    @Test
    void configuredDemoPhoneReceivesAnExplicitTestCode() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "demoPhone", "13800138000");

        Result result = service.setPhone("13800138000");

        assertTrue(result.getSuccess());
        assertTrue(((String) result.getData()).matches("[0-9]{6}"));
    }
}
