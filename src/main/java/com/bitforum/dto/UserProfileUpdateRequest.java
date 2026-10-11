package com.bitforum.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Schema(description = "用户资料更新请求")
public class UserProfileUpdateRequest {
    @Size(max = 255, message = "头像URL不能超过255个字符")
    @Schema(description = "旧版头像 URL 字段；新请求不可使用，请单独上传站内头像")
    private String avatar;

    @Size(max = 50, message = "昵称不能超过50个字符")
    @Schema(description = "昵称，最多 50 个字符", example = "论坛用户")
    private String nickname;

    @Size(max = 255, message = "个人简介不能超过255个字符")
    @Schema(description = "个人简介，最多 255 个字符", example = "热爱后端开发的社区成员")
    private String bio;
}
