package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IUserService extends IService<User> {
//短信验证相关业务
    Result setPhone(String phone);
//登录功能
    Result login(LoginFormDTO loginForm);

    Result sign();

    Result signCount();

    Result unLogin(String token);
}
