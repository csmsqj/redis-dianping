package com.hmdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnableTransactionManagement //开启注解方式的事务管理,如果不加这个注解，@Transactional注解就不起作
@EnableAspectJAutoProxy(exposeProxy = true) //exposeProxy = true表示暴露当前代理对象,以便在同一个类中调用被代理的方法时能够正确地应用事务
@SpringBootApplication
@MapperScan("com.hmdp.mapper")
public class HmDianPingApplication {
    public static void main(String[] args) {
        SpringApplication.run(HmDianPingApplication.class, args);
    }

}
