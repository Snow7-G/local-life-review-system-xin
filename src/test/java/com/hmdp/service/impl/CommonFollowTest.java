package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommonFollowTest {
    @AfterEach void clear() { UserHolder.removeUser(); }

    @Test
    void commonFollowsComeFromDatabaseAfterRedisLoss() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:commonFollow;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE tb_follow (user_id BIGINT, follow_user_id BIGINT)");
        jdbc.update("INSERT INTO tb_follow VALUES (7, 9), (8, 9), (7, 10)");
        IUserService users = mock(IUserService.class);
        when(users.listByIds(Collections.singletonList(9L)))
                .thenReturn(Collections.singletonList(new User().setId(9L).setNickName("共同关注")));
        FollowServiceImpl service = new FollowServiceImpl();
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        ReflectionTestUtils.setField(service, "userService", users);
        UserDTO current = new UserDTO();
        current.setId(7L);
        UserHolder.saveUser(current);

        Result result = service.commonFollow(8L);

        List<?> common = (List<?>) result.getData();
        assertEquals(1, common.size());
        assertEquals(9L, ((UserDTO) common.get(0)).getId());
    }
}
