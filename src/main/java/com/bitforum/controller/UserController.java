package com.bitforum.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bitforum.common.Result;
import com.bitforum.dto.LoginResponse;
import com.bitforum.dto.UserLoginRequest;
import com.bitforum.dto.UserRegisterRequest;
import com.bitforum.dto.UserResponse;
import com.bitforum.entity.User;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController                 // ← 声明这是一个 REST 控制器
@RequestMapping("/api/user")    // ← 这个 Controller 下所有接口都以 /api/user 开头
@Tag(name = "用户认证", description = "用户注册、登录和 JWT 获取")

public class UserController {
    @Autowired
    private UserService userService;

    // JwtUtil 已经交给 Spring 管理，登录时通过这个对象生成 Token
    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping("/register")       // ← POST /api/user/register
    @Operation(summary = "用户注册", description = "注册普通用户，响应不返回 password")
    public Result<UserResponse> register(@Valid @RequestBody UserRegisterRequest request) {
        // @RequestBody 从 JSON 请求体取参数，@Valid 触发 UserRegisterRequest 上的校验注解
        User user = userService.register(request.getUsername(),request.getPassword());
        if (user == null) {
            return Result.fail("注册失败，用户名已存在");
        }
        // 不直接返回 User 实体，避免把 password 字段带到响应里
        return Result.ok("注册成功", UserResponse.from(user));
    }


    @PostMapping("/login")          // ← POST /api/user/login
    @Operation(summary = "用户登录", description = "登录成功后返回 JWT Bearer Token")
    public Result<LoginResponse> login(@Valid @RequestBody UserLoginRequest request) {
        // 登录也使用 Request DTO，避免用户名和密码继续散落在 URL 查询参数中
        User user = userService.login(request.getUsername(),request.getPassword());
        if (user == null) {
            return Result.fail("登录失败，请重新输入用户名或密码");
        }
        // 登录成功后生成 Token；密钥和过期时间来自 application.yml 的 jwt 配置
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        // 用明确的 LoginResponse DTO 替代 Map，避免登录响应字段变得松散、不好维护
        LoginResponse response = new LoginResponse(token, user.getId(), user.getUsername(), user.getRole());
        return Result.ok("登录成功！", response);
    }
    
}
