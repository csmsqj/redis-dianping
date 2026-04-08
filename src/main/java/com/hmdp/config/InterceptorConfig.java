package com.hmdp.config;


import com.hmdp.interceptor.BeginInterceptor;
import com.hmdp.interceptor.BehindInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class InterceptorConfig implements WebMvcConfigurer {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new BehindInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns("/user/code",
                        "/user/login",
                        "/shop/**",
                        "/voucher/**",
                        "/upload/**",
                        "/blog/hot",
                        "/shop-type/**"
                ).order(1);
//配置开始拦截器，让它拦截所有
        registry.addInterceptor(new BeginInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")

                .order(0);
    }
}
