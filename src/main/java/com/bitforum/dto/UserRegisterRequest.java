package com.bitforum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Schema(description = "用户注册请求")
public class UserRegisterRequest {
    // 注册接口的入参 DTO：只接收前端允许提交的字段，并在进入业务层前做格式校验
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3,max = 30,message = "用户名长度需在3到30个字符之间")
    @Schema(description = "用户名，3 到 30 个字符", example = "bituser")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6,max = 30,message = "密码长度需在6到30个字符之间")
    @Schema(description = "密码，6 到 30 个字符", example = "123456")
    private String password;
}
