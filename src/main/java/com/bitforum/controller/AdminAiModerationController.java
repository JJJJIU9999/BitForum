package com.bitforum.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.ai.dto.ModerationFeedbackRequest;
import com.bitforum.ai.entity.AiModerationRecord;
import com.bitforum.ai.moderation.ModerationService;
import com.bitforum.common.Result;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 管理员 AI 审核接口（M16）。
 *
 * <p>位于 {@code /api/admin/**} 下，由既有 {@code AdminInterceptor} 统一鉴权，
 * 当前管理员 id 通过其写入的 {@code userId} 请求属性获取。
 *
 * <p>三个动作对应审核台的三件事：看记录（含待处理筛选）、标记已处理、反馈 AI 判断对错。
 * 注意这里**没有"AI 直接驳回内容"的接口** —— 按 M16 实施决策，
 * 驳回/删除始终由管理员通过原有的文章审核接口完成。
 */
@RestController
@RequestMapping("/api/admin/ai/moderation")
@Tag(name = "管理员内容审核", description = "AI 审核记录的查询、人工处理与反馈")
@SecurityRequirement(name = "bearerAuth")
public class AdminAiModerationController {

    @Autowired
    private ModerationService moderationService;

    /**
     * 分页查询审核记录。
     *
     * @param pendingOnly 传 true 时只返回待人工处理（priority &gt; 0 且未处理）的记录，
     *                    排序为「高优先级在前 + 时间倒序」，即管理台默认视图
     */
    @GetMapping("/records")
    @Operation(summary = "分页查询 AI 审核记录")
    public Result<Page<AiModerationRecord>> pageRecords(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Boolean pendingOnly) {
        Page<AiModerationRecord> page =
                moderationService.pageRecords(pageNum, pageSize, decision, targetType, pendingOnly);
        return Result.ok("审核记录查询成功", page);
    }

    @PostMapping("/records/{id}/feedback")
    @Operation(summary = "提交人工反馈（AI 判断对错）")
    public Result<Void> submitFeedback(@PathVariable Long id,
                                       @Valid @RequestBody ModerationFeedbackRequest request,
                                       @RequestAttribute("userId") Long adminId) {
        moderationService.submitFeedback(id, request.getFeedback(), adminId);
        return Result.ok("反馈已记录", null);
    }

    @PostMapping("/records/{id}/handle")
    @Operation(summary = "标记审核记录已处理")
    public Result<Void> markHandled(@PathVariable Long id,
                                    @RequestAttribute("userId") Long adminId) {
        moderationService.markHandled(id, adminId);
        return Result.ok("已标记为处理完毕", null);
    }
}
