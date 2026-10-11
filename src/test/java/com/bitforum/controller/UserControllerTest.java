package com.bitforum.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bitforum.entity.User;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest {
    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private JwtUtil jwtUtil;

    @Test
    void registerShouldFailWhenUsernameIsBlank() throws Exception {
        // 用 null 只触发 @NotBlank，避免空字符串同时触发 @Size 导致错误提示顺序不稳定
        String json = """
                {
                            "username": null,
                            "password": "123456"
                }
                """;

        // MockMvc 模拟一次真实 HTTP JSON 请求，不需要启动浏览器或 Postman
        mockMvc.perform(post("/api/user/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("用户名不能为空"));
    }

    @Test
    void loginShouldFailWhenPasswordIsBlank() throws Exception {
        // 登录密码为 null 时，只触发 UserLoginRequest.password 上的 @NotBlank
        String json = """
                {
                    "username": "testuser",
                    "password": null
                }
                """;

        mockMvc.perform(post("/api/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("密码不能为空"));
    }

    @Test
    void loginShouldReturnTokenAndUserInfoWhenSuccess() throws Exception {
        // 构造一个假的登录用户，模拟 UserService 查询数据库后的返回结果
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setRole(UserService.ROLE_ADMIN);

        // 用 Mockito 固定依赖返回值，让这个测试只关注 Controller 的响应格式
        when(userService.login("testuser", "123456")).thenReturn(user);
        when(jwtUtil.generateToken(1L, "testuser")).thenReturn("mock-token");

        // 发送 JSON 请求体，验证 @RequestBody + @Valid 的登录入口能正常接收参数
        String json = """
                        {
                        "username": "testuser",
                        "password": "123456"
                        }
                """;

        mockMvc.perform(post("/api/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("登录成功！"))
                // LoginResponse 会被序列化到 data 下，这里锁定 token 和用户基础信息不会丢
                .andExpect(jsonPath("$.data.token").value("mock-token"))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.username").value("testuser"))
                .andExpect(jsonPath("$.data.role").value(UserService.ROLE_ADMIN));
    }
}
