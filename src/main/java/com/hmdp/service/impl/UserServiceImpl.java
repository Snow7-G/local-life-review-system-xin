package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UpdateProfileDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.service.IUserInfoService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Arrays;
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
    private static final DefaultRedisScript<Long> CONSUME_CODE_SCRIPT = new DefaultRedisScript<>();
    static {
        CONSUME_CODE_SCRIPT.setLocation(new ClassPathResource("consumeLoginCode.lua"));
        CONSUME_CODE_SCRIPT.setResultType(Long.class);
    }
    @Value("${HMDP_DEMO_PHONE:}")
    private String demoPhone;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserInfoService userInfoService;

    @Override
    @Transactional
    public Result updateProfile(UpdateProfileDTO profile) {
        if (profile == null || profile.getNickName() == null || profile.getNickName().trim().isEmpty()
                || profile.getNickName().trim().length() > 32
                || profile.getIntroduce() != null && profile.getIntroduce().length() > 128) {
            return Result.fail("昵称或个人介绍格式不正确");
        }
        String icon = profile.getIcon();
        if (icon != null && !icon.isEmpty()
                && !icon.matches("/imgs/blogs/(?:[0-9]|1[0-5])/(?:[0-9]|1[0-5])/[0-9a-fA-F-]{36}\\.(jpg|jpeg|png|gif)")) {
            return Result.fail("头像路径不合法");
        }
        Long userId = UserHolder.getUser().getId();
        User user = new User().setId(userId).setNickName(profile.getNickName().trim());
        user.setIcon(icon == null ? UserHolder.getUser().getIcon() : icon);
        if (!updateById(user)) throw new IllegalStateException("用户资料保存失败");
        if (profile.getIntroduce() != null) {
            UserInfo info = userInfoService.getById(userId);
            if (info == null) {
                info = new UserInfo().setUserId(userId).setIntroduce(profile.getIntroduce());
                if (!userInfoService.save(info)) throw new IllegalStateException("个人介绍保存失败");
            } else {
                info.setIntroduce(profile.getIntroduce());
                if (!userInfoService.updateById(info)) throw new IllegalStateException("个人介绍保存失败");
            }
        }
        UserDTO updated = new UserDTO();
        updated.setId(userId);
        updated.setNickName(user.getNickName());
        updated.setIcon(user.getIcon() == null ? "" : user.getIcon());
        return Result.ok(updated);
    }
    @Override
    public Result setPhone(String phone) {
        //1,判断手机号码是否合法
        if(RegexUtils.isPhoneInvalid(phone)){
            //2,不合法，返回错误信息
            return Result.fail("手机号不合法，请重新输入");
        }
        if (!phone.equals(demoPhone)) {
            return Result.fail("短信服务尚未接入，请使用已配置的演示手机号");
        }
          String  limitKey =  LOGIN_CODE_LIMIT_KEY + phone;
        Boolean cansend = stringRedisTemplate.opsForValue().setIfAbsent(limitKey, "1", LOGIN_CODE_LIMIT_TTL, TimeUnit.SECONDS);
        if(!Boolean.TRUE.equals(cansend)){
            return Result.fail("请求过于频繁，请60秒后再试");
        }

        //3,合法，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4，将验证码放到redis里面
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5，发送验证码
        // 仅向明确配置的演示手机号返回验证码；生产短信服务尚未接入
        return Result.ok(code);
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
        String codeUser = loginForm.getCode();
        if (RegexUtils.isCodeInvalid(codeUser)) {
            return Result.fail("验证码错误");
        }
        Long codeResult = stringRedisTemplate.execute(CONSUME_CODE_SCRIPT,
                Arrays.asList(LOGIN_CODE_KEY + phone, LOGIN_CODE_ATTEMPTS_KEY + phone),
                codeUser, String.valueOf(LOGIN_CODE_MAX_ATTEMPTS), String.valueOf(LOGIN_CODE_TTL * 60));
        if (Long.valueOf(-2).equals(codeResult)) {
            return Result.fail("验证码尝试次数过多，请稍后重试");
        }
        if (!Long.valueOf(1).equals(codeResult)) {
            return Result.fail("验证码错误或已失效");
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
//登出功能
    @Override
    public Result unLogin(String token) {
        String key = LOGIN_USER_KEY + token;
        if(token == null){
            return Result.fail("未登录");
        }
        stringRedisTemplate.delete(key);
        UserHolder.removeUser();
        return Result.ok("登出成功");
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
