package com.hmdp.service.impl;

import com.hmdp.dto.UpdateProfileDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserProfileTest {
    private UserServiceImpl service;
    private IUserInfoService infoService;

    @BeforeEach
    void setUp() {
        UserDTO current = new UserDTO();
        current.setId(7L);
        UserHolder.saveUser(current);
        service = spy(new UserServiceImpl());
        infoService = mock(IUserInfoService.class);
        ReflectionTestUtils.setField(service, "userInfoService", infoService);
    }

    @AfterEach
    void tearDown() { UserHolder.removeUser(); }

    @Test
    void updatesOnlyAuthenticatedUserAndIntroduction() {
        UpdateProfileDTO input = new UpdateProfileDTO();
        input.setNickName("  新昵称  ");
        input.setIcon("/imgs/blogs/10/15/123e4567-e89b-12d3-a456-426614174000.png");
        input.setIntroduce("新的介绍");
        doReturn(true).when(service).updateById(any(User.class));
        when(infoService.getById(7L)).thenReturn(null);
        when(infoService.save(any(UserInfo.class))).thenReturn(true);

        assertTrue(service.updateProfile(input).getSuccess());
        org.mockito.ArgumentCaptor<User> user = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(service).updateById(user.capture());
        assertEquals(7L, user.getValue().getId());
        assertEquals("新昵称", user.getValue().getNickName());
        org.mockito.ArgumentCaptor<UserInfo> info = org.mockito.ArgumentCaptor.forClass(UserInfo.class);
        verify(infoService).save(info.capture());
        assertEquals(7L, info.getValue().getUserId());
        assertEquals("新的介绍", info.getValue().getIntroduce());
    }

    @Test
    void rejectsExternalImageAndBlankNickname() {
        UpdateProfileDTO input = new UpdateProfileDTO();
        input.setNickName(" ");
        assertFalse(service.updateProfile(input).getSuccess());
        input.setNickName("合法昵称");
        input.setIcon("https://example.com/track.png");
        assertFalse(service.updateProfile(input).getSuccess());
        verify(service, never()).updateById(any(User.class));
    }
}
