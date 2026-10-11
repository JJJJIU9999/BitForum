package com.bitforum.interceptor;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.bitforum.entity.User;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class LoginInterceptor implements HandlerInterceptor {
    // 拦截器也由 Spring 管理，所以可以注入 JwtUtil 来解析 Token
    private final JwtUtil jwtUtil;
    private final UserService userService;

    // 构造器注入：创建 LoginInterceptor 时，Spring 会把 JwtUtil 传进来
    // 把外面Spring传进来的jwtUtile保存到这个类里面的jwtUtile
    public LoginInterceptor(JwtUtil jwtUtil, UserService userService) {
        this.jwtUtil = jwtUtil;
        this.userService = userService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,Object handler)throws Exception {
        //1、取Authorization头
        String authHeader = request.getHeader("Authorization");

        //2、为空格或格式不对 -> 401
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"请先登录\"}");
            return false;
        }

        //3、截取 "Bearer" 后面的Token
        String token = authHeader.substring(7);

        //4、验证Token(篡改、过期都会抛异常)
        Long userId;
        try{
            // 解析 Token 中的 userId，后续 Controller 可以通过 @RequestAttribute 获取
            userId = jwtUtil.getUserId(token); // 用刚写的工具类解析
        } catch (Exception e) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"Token无效或已过期\"}");
            return false;
        }
        User user = userService.findById(userId);
        if (user == null || !Integer.valueOf(UserService.STATUS_ENABLED).equals(user.getStatus())) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"账号不可用\"}");
            return false;
        }
        request.setAttribute("userId", userId); //把userId暂存起来，Controller里能拿到
        return true;
    }
}
