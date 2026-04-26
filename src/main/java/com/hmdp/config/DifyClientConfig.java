package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
@Configuration
public class DifyClientConfig {
    @Bean
    public RestClient difyRestClient(DifyProperties properties) {
        return RestClient.builder()
                // 所有 Dify 请求都会基于这个 baseUrl，不需要每次重复写完整地址。
                .baseUrl(properties.getBaseUrl())

                // Dify API 需要 Bearer Token 鉴权。
                // 放在默认请求头里，后面每次调用 Dify 都会自动携带。
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())

                // 告诉 Dify：我发送的是 JSON。
                // Spring 会自动把 Java 对象转换成 JSON 请求体。
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
