package com.bitforum.dto;

import java.time.LocalDateTime;

import com.bitforum.entity.User;

import lombok.Data;

@Data
public class AdminUserResponse {
    private Long userId;
    private String username;
    private String avatar;
    private String role;
    private Integer status;
    private LocalDateTime createTime;

    public static AdminUserResponse from(User user) {
        // 管理员用户列表也不能返回 password；这里只挑选后台需要展示的安全字段。
        AdminUserResponse response = new AdminUserResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setAvatar(user.getAvatar());
        response.setRole(user.getRole());
        response.setStatus(user.getStatus());
        response.setCreateTime(user.getCreateTime());
        return response;
    }
}
