package com.bitforum.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {
    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI bitForumOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("BitForum 社区交流平台接口文档")
                        .version("1.0.0")
                        .description("""
                                基于 Spring Boot 与 React 的社区交流平台接口文档。
                                当前覆盖用户认证、文章、评论、板块、审核、收藏、通知、举报、管理员看板和用户主页等接口。
                                需要登录的接口使用 Authorization: Bearer <token>，公开查询接口无需登录。
                                """))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录后在请求头中传入 Authorization: Bearer <token>")));
    }
}
