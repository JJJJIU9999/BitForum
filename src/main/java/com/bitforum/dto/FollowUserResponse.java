package com.bitforum.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "关注或粉丝用户公开信息")
public class FollowUserResponse {
    @Schema(description = "用户 ID")
    private Long userId;
    @Schema(description = "用户名")
    private String username;
    @Schema(description = "头像 URL")
    private String avatar;
    @Schema(description = "昵称")
    private String nickname;
    @Schema(description = "个人简介")
    private String bio;
    @Schema(description = "账号状态")
    private Integer status;
    @Schema(description = "关注关系创建时间")
    private LocalDateTime followedAt;
}

