package com.bitforum.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {
    // 登录成功后返回给前端的响应 DTO，明确声明 token、用户基础信息和角色展示信息。
    private String token;
    private Long userId;
    private String username;
    private String role;
}
