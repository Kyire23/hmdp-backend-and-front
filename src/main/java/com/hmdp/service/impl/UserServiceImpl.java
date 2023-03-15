package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.*;
import jdk.nashorn.internal.runtime.regexp.joni.Regex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author
 * @since 2021-12-22
 */
@Slf4j
@Service("userService")
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1. 校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2. 不符合，返回错误信息
            return Result.fail("手机号码格式错误！");
        }
        //3. 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        String key = RedisConstants.LOGIN_CODE_KEY;
        //4. 保存验证码到redis
        stringRedisTemplate.opsForValue().set( key + phone,code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.SECONDS);

        //5. 发送验证码 实际项目借助第三方平台API
        log.debug("发送短信验证码成功,接收手机号：{} => 验证码：{}",phone,code);

        //返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        String phone = loginForm.getPhone();

        //1. 校验手机号和验证码
        if(RegexUtils.isPhoneInvalid(phone))  return Result.fail("手机号码格式错误！");

        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();

        if(cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误！");
        }

        //2. 一致 根据手机号查用户
        User user = query().eq("phone", phone).one();

        //3. 判断用户是否存在
        if(user == null){
            //不存在，创建新的用户
            user = createUserWithPhone(phone);
        }

        //4. 保存用户到redis
        //4.1 随机生成token, 作为登陆令牌
        String token = UUID.randomUUID().toString(true);  //简单的uuid（不带_）
        //4.2 将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, String> userMap = ObjectToMap.UserDto2Map(userDTO);
        //4.3 存储
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token, userMap);
        //4.4 设置token有效时间
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);

        //4.3 将token返回给客户端
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        // 1. 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2. 保存用户
        save(user);
        return user;
    }

    @Override
    public Result sign() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 获取日期 拼接Key
        LocalDateTime now = LocalDateTime.now();

        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId +format;

        // BitMap底层是基于String数据结构，因此其操作也都封装在字符串相关操作中
        int dayOfMonth = now.getDayOfMonth()-1;
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth,true);
        return Result.ok();
    }

    @Override
    public Result logout() {
        UserHolder.removeUser();
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 获取日期 拼接Key
        LocalDateTime now = LocalDateTime.now();

        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId +format;
        //获取本月第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取本月截止今天为止的所有的签到记录，返回是一个十进制的数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );

        if (result == null || result.isEmpty()){
            //没有任何签到结果
            return Result.ok(0);
        }

        Long num = result.get(0);
        if (num == null || num == 0){
            return Result.ok(0);
        }
        //循环遍历
        int count = 0;
        while (true){
            //让这个数字与1做与运算，得到数字的最后一个bit位
            //判断这个bit位是否为0
            if ((num & 1) == 0){
                //如果0，说明未签到，结束
                break;
            }else {
                //如果不0，说明已签到，计数器+1
                count ++;
            }
            //把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }
}