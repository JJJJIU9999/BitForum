package com.bitforum.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bitforum.ai.analyst.AiInsightGenerationService;
import com.bitforum.ai.analyst.AiInsightService;
import com.bitforum.ai.entity.AiInsightReport;
import com.bitforum.common.Result;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 管理员「AI 运营洞察」接口（M17）。
 *
 * <p>落地产品决策第 4 条：**管理员主动触发、异步生成、结果落库**。
 * 生成要调用大模型（实测数秒），因此 {@code /generate} 只负责受理并立刻返回，
 * 前端凭返回的 PENDING 记录轮询 {@code /status}。
 *
 * <p>整个 {@code /api/admin/**} 已由 {@code AdminInterceptor} 保护，无需逐个方法声明权限。
 */
@RestController
@RequestMapping("/api/admin/ai/insight")
@Tag(name = "管理员 AI 运营洞察", description = "异步生成社区运营洞察报告")
@SecurityRequirement(name = "bearerAuth")
public class AdminAiInsightController {

    @Autowired
    private AiInsightGenerationService generationService;

    @Autowired
    private AiInsightService insightService;

    @PostMapping("/generate")
    @Operation(summary = "触发一次运营洞察生成",
            description = "立即返回（不等待模型）。若已有生成中的任务则拒绝，避免连点重复消耗额度")
    public Result<AiInsightReport> generate(@RequestAttribute("userId") Long adminId) {
        Optional<AiInsightReport> report = generationService.trigger(adminId);
        if (report.isEmpty()) {
            return Result.fail("已有一份运营洞察正在生成，请稍候");
        }
        return Result.ok("已开始生成运营洞察，稍后刷新即可查看", report.get());
    }

    /** 看板卡片用：最新一份成功报告。从没生成过时 data 为空。 */
    @GetMapping("/latest")
    @Operation(summary = "查询最新一份运营洞察报告",
            description = "只返回**成功**的报告；失败记录留痕供排查，但不会被当成当前洞察展示")
    public Result<AiInsightReport> latest() {
        return insightService.latestSuccess()
                .map(report -> Result.ok("查询成功", report))
                .orElseGet(() -> Result.ok("暂无运营洞察报告，可点击生成", null));
    }

    /** 前端轮询用：当前是否有生成中的任务。 */
    @GetMapping("/status")
    @Operation(summary = "查询运营洞察生成状态",
            description = "有生成中的任务时返回该记录（status=PENDING），否则 data 为空")
    public Result<AiInsightReport> status() {
        return insightService.pending()
                .map(report -> Result.ok("正在生成运营洞察", report))
                .orElseGet(() -> Result.ok("当前没有生成中的任务", null));
    }

    @GetMapping("/history")
    @Operation(summary = "查询运营洞察历史", description = "按时间倒序，最多 50 条")
    public Result<List<AiInsightReport>> history(
            @RequestParam(defaultValue = "10") Integer limit) {
        return Result.ok("查询成功", insightService.recent(limit == null ? 10 : limit));
    }
}
