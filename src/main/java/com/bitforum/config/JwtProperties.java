package com.bitforum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

// 用来接收 application.yml 里 jwt 开头的配置
@Data
// 交给 Spring 管理，这样 JwtUtil 里才能通过构造器注入它
@Component
// 把 jwt.secret、jwt.expiration 自动绑定到下面的字段
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {
    // JWT 签名密钥，对应 application.yml 中的 jwt.secret
    private String secret;

    // Token 过期时间，单位是毫秒，对应 application.yml 中的 jwt.expiration
    private long expiration;
}
