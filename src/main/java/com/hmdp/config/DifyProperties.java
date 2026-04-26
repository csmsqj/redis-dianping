package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Data
@ConfigurationProperties(prefix = "agent.dify")
public class DifyProperties {

    /**
     * Dify API 的基础地址，例如：https://api.dify.ai/v1。
     *
     * 为什么放到配置文件：
     * 1. 本地、测试、线上环境的 Dify 地址可能不同；
     * 2. Java 代码只关心“我要调用 Dify”，不应该写死具体环境地址；
     * 3. 后续部署时只改 application-dev.yaml 或环境变量，不需要重新改代码。
     */
    private String baseUrl;
    /**
     * Dify 应用的 API Key。
     *
     * 为什么不能写死在前端或业务代码里：
     * 1. API Key 类似密码，暴露后别人可以直接调用你的 Dify 应用；
     * 2. 放到后端配置中，可以通过环境变量 DIFY_API_KEY 注入；
     * 3. 后端 RestClient 会统一把它放进 Authorization 请求头。
     */
    private String apiKey;
    public String getBaseUrl() {
        return baseUrl;
    }
    public void setBaseUrl(String baseUrl) {
        // Spring Boot 会通过 setter 把 agent.dify.baseUrl 绑定到这个字段。
        this.baseUrl = baseUrl;
    }

}
