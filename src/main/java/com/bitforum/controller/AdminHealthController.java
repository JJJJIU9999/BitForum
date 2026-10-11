package com.bitforum.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bitforum.common.Result;
import com.bitforum.dto.AdminHealthResponse;
import com.bitforum.service.AdminHealthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/admin/health")
@Tag(name = "管理员健康检查", description = "管理员查看应用、MySQL、Redis 和 RabbitMQ 健康状态")
@SecurityRequirement(name = "bearerAuth")
public class AdminHealthController {
    @Autowired
    private AdminHealthService adminHealthService;

    @GetMapping
    @Operation(summary = "查询管理员健康检查", description = "聚合应用、MySQL、Redis 和 RabbitMQ 状态；需要管理员权限")
    public Result<AdminHealthResponse> health() {
        return Result.ok("管理员健康检查查询成功", adminHealthService.check());
    }
}
