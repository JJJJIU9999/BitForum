package com.bitforum.interceptor;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.bitforum.entity.User;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AdminInterceptor implements HandlerInterceptor {
    private final JwtUtil jwtUtil;
    private final UserService userService;

    public AdminInterceptor(JwtUtil jwtUtil, UserService userService) {
        this.jwtUtil = jwtUtil;
        this.userService = userService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeJson(response, 401, "请先登录");
            return false;
        }

        try {
            String token = authHeader.substring(7);
            Long userId = jwtUtil.getUserId(token);

            // 管理员权限不能只相信 token。这里每次根据 userId 查数据库，确保账号状态和角色是最新的。
            User user = userService.findById(userId);
            if (user == null || !Integer.valueOf(UserService.STATUS_ENABLED).equals(user.getStatus())) {
                writeJson(response, 403, "账号不可用");
                return false;
            }

            if (!UserService.ROLE_ADMIN.equals(user.getRole())) {
                writeJson(response, 403, "无管理员权限");
                return false;
            }

            // 管理员接口后续如果需要当前用户 id，可以继续通过 @RequestAttribute("userId") 获取。
            request.setAttribute("userId", userId);
            return true;
        } catch (Exception e) {
            writeJson(response, 401, "Token无效或已过期");
            return false;
        }
    }

    private void writeJson(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\"}");
    }
}
