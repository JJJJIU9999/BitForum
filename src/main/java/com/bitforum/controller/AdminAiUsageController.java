package com.bitforum.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bitforum.ai.dto.AiUsageOverviewResponse;
import com.bitforum.ai.usage.AiUsageQueryService;
import com.bitforum.common.Result;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 管理员 AI 用量概览接口（M18）。
 *
 * <p>位于 {@code /api/admin/**} 下，由既有 {@code AdminInterceptor} 统一鉴权。
 * 用量与费用属于运营信息，因此只对管理员开放（M18 决策 Q5）。
 *
 * <p>返回里带上了计价单价与口径说明：页面上的成本是估算值，
 * 把单价一起展示，看数据的人才知道这个数字是怎么算出来的。
 */
@RestController
@RequestMapping("/api/admin/ai/usage")
@Tag(name = "管理员 AI 用量统计", description = "按用户 / Agent / 天 的 token 与成本概览（M18）")
@SecurityRequirement(name = "bearerAuth")
public class AdminAiUsageController {

    @Autowired
    private AiUsageQueryService usageQueryService;

    /**
     * 用量概览。
     *
     * @param days 统计最近多少天（含今天），取值会被收敛到 1~90
     */
    @GetMapping("/overview")
    @Operation(summary = "查询 AI 用量概览（总量 / 按天 / 按 Agent / Top 用户）")
    public Result<AiUsageOverviewResponse> overview(@RequestParam(defaultValue = "7") int days) {
        return Result.ok("AI 用量概览查询成功", usageQueryService.overview(days));
    }
}
