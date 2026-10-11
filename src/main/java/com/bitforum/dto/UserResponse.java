package com.bitforum.dto;

import java.time.LocalDateTime;

import com.bitforum.entity.User;

import lombok.Data;

@Data
public class UserResponse {
    // 返回给前端看的用户信息，不包含 password，避免注册接口泄露密码哈希
    private Long id;
    private String username;
    private String avatar;
    private LocalDateTime createTime;

    // 把数据库实体 User 转成响应 DTO，只挑选允许暴露给前端的字段
    public static UserResponse from(User user) {
        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setAvatar(user.getAvatar());
        response.setCreateTime(user.getCreateTime());
        return response;
    }
}
