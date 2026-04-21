package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexPatterns;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private UserMapper userMapper;
    @Override
    //发送验证码
    public Result sendCode(String phone, HttpSession session) {

        // 1.校验手机号
        if(phone == null || phone.length() != 11||!phone.matches(RegexPatterns.PHONE_REGEX)){
            return Result.fail("手机号格式错误");
        }
        //糊涂包生成验证码
        String numbers = RandomUtil.randomNumbers(6);
        log.info("生成验证码成功，验证码：{}", numbers);
//将验证码保存到 Redis 当中
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, numbers,
                RedisConstants.LOGIN_CODE_TTL
                , java.util.concurrent.TimeUnit.MINUTES);

        return Result.ok();
    }

    @Override
    //登录功能
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号and验证码
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        if(phone==null || phone.length() != 11||!phone.matches(RegexPatterns.PHONE_REGEX)){
            return Result.fail("手机号格式错误");
        }
        String rediscode = stringRedisTemplate.opsForValue().get("login:code:" + phone);
        if(code==null||!code.matches(RegexPatterns.VERIFY_CODE_REGEX)||!code.equals(rediscode)){
            return Result.fail("验证码错误");
        }
        // 2.查询用户
        User user = query().eq("phone", phone).one();
        // 3.不存在则创建
        if(user==null) {
            userMapper.createUserWithPhone(phone);
user=userMapper.findByPhone(phone);

        }
// 4.保存用户信息,保存到 Redis 当中从而做到可以用内存读并且可同时被多个服务器所共享
 //我有敏感信息是不能存的，所以说封装为 dtol 再存到 Redis
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        //给DTO 通过胡图包随机生成姓名
        userDTO.setNickName("用户"+RandomUtil.randomString(6));
        //通过胡图包下的 BeanUtiL 将对象转化为 map 类型
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((name, value) -> {
                    return value.toString();
                })
        );
        //完成随机 TOKeN 作为登录令牌使用 UUID
        String token = UUID.randomUUID().toString(true);
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+ token, stringObjectMap);
stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+ token, RedisConstants.LOGIN_USER_TTL
        , TimeUnit.MINUTES);
return Result.ok(token);
    }

    @Override
    public Result sign() {
        //实现每日签到功能,签到功能需要用到 Redis 的 bitMap 数据结构，
        // bitMap可以看成是一个二进制数组，每个二进制位代表一个用户的签到状态，0 代表未签到，1 代表已签到
        LocalDate now = LocalDate.now();
        String format = now.format(DateTimeFormatter.ofPattern("yyyy:MM"));
        int dayOfMonth = now.getDayOfMonth();
String key="sign:"+format;
stringRedisTemplate.opsForValue().setBit(key, dayOfMonth-1, true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
        LocalDate now = LocalDate.now();
        //这里以年与月份作为前缀，这里要日期存放在月份底下，日期作为值
        //如果日期作为前缀,会导致一个日期就有一个 key不符合我们的逻辑
        String format = now.format(DateTimeFormatter.ofPattern("yyyy:MM"));
        int dayOfMonth = now.getDayOfMonth();
        String key="sign:"+format;
        List<Long> longs = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create()
                //无符号开始，总共要获取的位数，位数为今天的日期
                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                .valueAt(0)
        );
if(CollectionUtils.isEmpty(longs)) {

return Result.fail("查询签到数量失败");
}
        Long l = longs.get(0);
int cnt=0;
while(true) {
    if ((l & 1) == 0) {
break;
    }
else{
cnt++;
l=l>>1;
    }

}
        return Result.ok(cnt);
    }
}
