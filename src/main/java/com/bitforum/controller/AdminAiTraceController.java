package com.bitforum.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.ai.dto.AiTraceResponse;
import com.bitforum.ai.trace.AiTraceQueryService;
import com.bitforum.common.Result;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 管理员 AI 执行轨迹接口（M18）。
 *
 * <p>位于 {@code /api/admin/**} 下，由既有 {@code AdminInterceptor} 统一鉴权，
 * 因此这里不需要再往 {@code WebMvcConfig} 加登录拦截（M17 踩过的坑：
 * 需要 userId 的接口才要单独加，本控制器不读当前管理员身份）。
 *
 * <p>只读接口：轨迹由 {@code TraceRecorder} 在各条 AI 链路上自动写入，
 * 管理员只能查、不能改 —— 可观测性数据的价值在于如实，允许编辑就等于失去意义。
 */
@RestController
@RequestMapping("/api/admin/ai/traces")
@Tag(name = "管理员 AI 执行轨迹", description = "AI 调用的执行轨迹查询（M18）")
@SecurityRequirement(name = "bearerAuth")
public class AdminAiTraceController {

    @Autowired
    private AiTraceQueryService traceQueryService;

    /**
     * 分页查询执行轨迹。
     *
     * @param scene  场景过滤：CHAT / MODERATION / INSIGHT / RECOMMEND
     * @param status 状态过滤：RUNNING / SUCCESS / DEGRADED / FAILED
     * @param userId 只看某个用户触发的调用
     */
    @GetMapping
    @Operation(summary = "分页查询 AI 执行轨迹")
    public Result<Page<AiTraceResponse>> pageTraces(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId) {
        return Result.ok("执行轨迹查询成功",
                traceQueryService.pageTraces(pageNum, pageSize, scene, status, userId));
    }

    /** 按 traceId 查一条轨迹的完整链路（含每一步的耗时与 token）。 */
    @GetMapping("/{traceId}")
    @Operation(summary = "查询单条执行轨迹详情（含步骤明细）")
    public Result<AiTraceResponse> detail(@PathVariable String traceId) {
        AiTraceResponse trace = traceQueryService.findByTraceId(traceId);
        if (trace == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "执行轨迹不存在：" + traceId);
        }
        return Result.ok("执行轨迹查询成功", trace);
    }
}
