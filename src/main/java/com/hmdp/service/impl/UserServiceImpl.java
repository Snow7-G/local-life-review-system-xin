package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result setPhone(String phone) {
        //1,判断手机号码是否合法
        if(RegexUtils.isPhoneInvalid(phone)){
            //2,不合法，返回错误信息
            return Result.fail("手机号不合法，请重新输入");
        }

        //3,合法，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4，将验证码放到redis里面
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5，发送验证码
        log.info("验证码已发送成功，{}",code);
        //6,返回发送成功信息
        return Result.ok();
    }
//登录功能
    @Override
    public Result login(LoginFormDTO loginForm) {
        String phone = loginForm.getPhone();
        //1，判断手机号是否合法
        if(RegexUtils.isPhoneInvalid(phone)){
            //2,不合法，返回错误信息
            return Result.fail("手机号不合法，请重新输入");
        }
        //2,判断验证码是否一样
            //2.1 获取用户输入的验证码
        String codeUser = loginForm.getCode();
            //2.2 获取redis中正确的验证码
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if(codeUser == null || !codeUser.equals(code)){
            //3，不一样，返回错误信息
            return Result.fail("验证码错误");
        }
        //4,一样，查询用户是否存在
        User user = query().eq("phone", phone).one();
        if(user == null){
            //5，不存在，创建用户
           user =  createUser(phone);
        }
        //6,存在，将用户信息放到redis里面
        //6.1 创建一个token
        String token = UUID.randomUUID().toString();
        //6.2 将userdto转化为hashmap存储
        UserDTO userDTO =  BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fileName,fileValue)->fileValue.toString())
        );

        //6.3存入redis
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,userMap);
        //6.4 定义存储时间
        stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL,TimeUnit.MINUTES);
        log.info("登陆成功");
        return Result.ok(token);
    }
//用户签到
    @Override
    public Result sign() {
        //1，获得当前登录用户id
        Long id = UserHolder.getUser().getId();
        //2，获得当前时间
        LocalDateTime now = LocalDateTime.now();
        String nowStr = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //3，拼接字符串组成key
        String key = USER_SIGN_KEY + id + nowStr;
        //4，确定当前是一个月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5，存入reids
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth - 1,true);
        return Result.ok();
    }
    //统计签到次数

    @Override
    public Result signCount() {
        //1，获得当前登录用户id
        Long id = UserHolder.getUser().getId();
        //2，获得当前时间
        LocalDateTime now = LocalDateTime.now();
        String nowStr = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //3，拼接字符串组成key
        String key = USER_SIGN_KEY + id + nowStr;
        //4，确定当前是一个月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5. 获取所有签到 BITFIELD key GET u[dayOfMonth] 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0));
        if(result == null || result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0){
            return Result.ok(0);
        }
        int count = 0;
        //6. 循环签到
        while (true){
            //6.1 与1做与预算
            if((num & 1) == 0){
                //结果为0，结束
                break;
            }else{
                //结果为1，计数器+1
                count++;
            }
            //将最后一位向右移一位，抛出刚刚参与运算的一位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        save(user);
        return user;

    }
}
