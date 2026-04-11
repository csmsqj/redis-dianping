package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BeginInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;
    public BeginInterceptor(StringRedisTemplate stringRedisTemplate){

        this.stringRedisTemplate=stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader("Authorization");
// tOKEN 不存在或者为空fangxing
        if(StrUtil.isBlank(token)){
            return true;
        }
        // 通过token获取用户信息
        Map<Object, Object> m = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
        if(m.isEmpty()){
            return true;
        }
        //通过如图包下的 UTIL 方法，将 MAP 转化为对象
        UserDTO userDTO = new UserDTO();
        BeanUtil.fillBeanWithMap(m,userDTO,false);
// 存在的话，那么将信息存到 THREADLocal当中   (为在线程池当中分配线程安全的空间)
        //在该次请求当中分配的线程是不变的，刚好通过拦截器保存相关信息
        //在登录之后的请求带的 COKIE 就会解析到这一步
        //有的请求并不需要存入线程池，但是带着的话可以和后面的拦截器一起验证
        UserHolder.saveUser(userDTO);
        // 刷新token有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        return true;

    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {

        UserHolder.removeUser();
    }

}
