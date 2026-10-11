package com.bitforum.ai.moderation;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.ai.entity.AiModerationRecord;
import com.bitforum.ai.mapper.AiModerationRecordMapper;
import com.bitforum.service.ArticleService;

/**
 * 内容审核服务（M16）：把 AI 判断翻译成系统动作，并落库。
 *
 * <p><b>本类是 M16 实施决策的落地点</b>（见 task_plan.md 的 M16 实施决策）：
 *
 * <ol>
 *   <li><b>Decision 与 Action 解耦</b>：{@link ModerationAgent} 只负责判断，
 *       本类按确定性规则决定系统动作，两者分别落库。</li>
 *   <li><b>自动 PASS 但不自动 REJECT</b>：唯一可能的自动动作是文章「高置信 PASS 自动放行」，
 *       且需要开关开启 + 置信度达标 + 风险分达标；<b>REJECT 永远只生成高优先级待处理记录</b>。</li>
 *   <li><b>阈值不预设</b>：配置默认值刻意设为"永不满足"（confidence ≥ 1 且 risk < 0），
 *       即使误开开关也不会放行未经验证的内容；阈值由评测实验确定后回填。</li>
 * </ol>
 */
@Service
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    /**
     * 系统自动放行时写入审核记录的"操作者 id"。
     *
     * <p>用 0 而不是某个管理员 id：自动放行不是人工决定的，
     * 事后复盘时必须能区分"人审核通过"与"系统自动放行"。
     */
    public static final long SYSTEM_OPERATOR_ID = 0L;

    /**
     * 一次审核的处理结果。
     *
     * <p>M18 增加 {@code degraded} / {@code errorMessage} / {@code model} / token 三项：
     * 轨迹要能回答"这次审核是不是走了降级"，用量要能按 Agent 统计 token ——
     * 而审核链路此前完全没有 token 数据。
     */
    public record ModerationResult(
            ModerationDecision decision,
            ModerationAction action,
            boolean autoApproved,
            Long recordId,
            boolean degraded,
            String errorMessage,
            String model,
            long latencyMillis,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens) {
    }

    /** 由确定性规则得出的动作计划 */
    private record ActionPlan(ModerationAction action, String reason) {
    }

    private final ModerationAgent moderationAgent;
    private final AiModerationRecordMapper recordMapper;
    private final ArticleService articleService;

    private final boolean autoApproveEnabled;
    private final double autoApproveConfidence;
    private final double autoApproveMaxRisk;

    public ModerationService(
            ModerationAgent moderationAgent,
            AiModerationRecordMapper recordMapper,
            ArticleService articleService,
            @Value("${bitforum.ai.moderation.auto-approve-enabled:false}") boolean autoApproveEnabled,
            @Value("${bitforum.ai.moderation.auto-approve-confidence:1.0}") double autoApproveConfidence,
            @Value("${bitforum.ai.moderation.auto-approve-max-risk:0.0}") double autoApproveMaxRisk) {
        this.moderationAgent = moderationAgent;
        this.recordMapper = recordMapper;
        this.articleService = articleService;
        this.autoApproveEnabled = autoApproveEnabled;
        this.autoApproveConfidence = autoApproveConfidence;
        this.autoApproveMaxRisk = autoApproveMaxRisk;
    }

    /**
     * 审核一条内容：分析 → 决定动作 → 落库 → 必要时执行动作。
     *
     * <p>本方法不抛异常：审核失败不应影响内容发布主流程，
     * 失败时会留下一条 {@code ANALYSIS_FAILED} 记录，由人工按原流程处理。
     */
    public ModerationResult moderate(
            ModerationTargetType targetType, Long targetId, String content, Long authorId) {

        ModerationAgent.ModerationOutcome outcome = moderationAgent.analyze(targetType, content);
        ActionPlan plan = decideAction(targetType, outcome);
        AiModerationRecord record = saveRecord(targetType, targetId, content, authorId, outcome, plan);

        boolean autoApproved = false;
        if (plan.action() == ModerationAction.AUTO_APPROVED) {
            autoApproved = applyAutoApprove(targetType, targetId, record);
        }

        log.info(">>> 审核处理：type={}，targetId={}，decision={}，action={}，自动放行={}",
                targetType, targetId, outcome.assessment() == null ? "REVIEW(兜底)" : outcome.assessment().decisionEnum(),
                plan.action(), autoApproved);

        ModerationDecision decision = outcome.assessment() == null
                ? ModerationDecision.REVIEW
                : outcome.assessment().decisionEnum();
        return new ModerationResult(decision, plan.action(), autoApproved, record.getId(),
                outcome.degraded(), outcome.errorMessage(), outcome.model(), outcome.latencyMillis(),
                outcome.promptTokens(), outcome.completionTokens(), outcome.totalTokens());
    }

    /**
     * 确定性动作规则 —— 决策与动作解耦的具体实现。
     *
     * <p>注意两处刻意的设计：
     * <ul>
     *   <li>REJECT 在任何情况下都不会产生"自动驳回"动作，只生成高优先级待处理记录；</li>
     *   <li>评论判定 PASS 不需要任何动作（评论发布即公开，放行没有意义）。</li>
     * </ul>
     */
    private ActionPlan decideAction(ModerationTargetType targetType, ModerationAgent.ModerationOutcome outcome) {
        if (outcome.degraded() || outcome.assessment() == null) {
            return new ActionPlan(ModerationAction.ANALYSIS_FAILED,
                    "AI 分析失败（" + outcome.errorMessage() + "），按原有人工流程处理");
        }

        ModerationAssessment assessment = outcome.assessment();
        ModerationDecision decision = assessment.decisionEnum();

        if (decision == ModerationDecision.REJECT) {
            return new ActionPlan(ModerationAction.HIGH_PRIORITY_REVIEW,
                    "AI 判定 REJECT（最高风险维度 %s=%.2f），生成高优先级待处理记录，不自动驳回"
                            .formatted(assessment.maxDimension(), assessment.maxScore()));
        }

        if (decision == ModerationDecision.PASS) {
            if (targetType == ModerationTargetType.COMMENT) {
                return new ActionPlan(ModerationAction.NO_ACTION, "评论判定 PASS（发布即公开，无需处理）");
            }
            return decideArticlePassAction(assessment);
        }

        return new ActionPlan(ModerationAction.PENDING_REVIEW,
                "AI 判定 REVIEW，转人工复核");
    }

    /** 文章判定 PASS 时，判断是否满足自动放行条件。 */
    private ActionPlan decideArticlePassAction(ModerationAssessment assessment) {
        if (!autoApproveEnabled) {
            return new ActionPlan(ModerationAction.PENDING_REVIEW,
                    "判定 PASS，但自动放行开关未开启，等待人工审核");
        }
        if (assessment.confidence() < autoApproveConfidence) {
            return new ActionPlan(ModerationAction.PENDING_REVIEW,
                    "判定 PASS，但置信度 %.2f 低于阈值 %.2f".formatted(assessment.confidence(), autoApproveConfidence));
        }
        if (assessment.maxScore() >= autoApproveMaxRisk) {
            return new ActionPlan(ModerationAction.PENDING_REVIEW,
                    "判定 PASS，但风险分 %.2f 未低于阈值 %.2f"
                            .formatted(assessment.maxScore(), autoApproveMaxRisk));
        }
        return new ActionPlan(ModerationAction.AUTO_APPROVED,
                "判定 PASS 且置信度 %.2f、风险分 %.2f 均达标，系统自动放行"
                        .formatted(assessment.confidence(), assessment.maxScore()));
    }

    /**
     * 执行自动放行。
     *
     * <p>复用 {@code ArticleService.approve}，因此自动放行会与人工审核一样触发
     * 审核记录、作者通知与知识库索引，不会绕过既有业务流程。
     * 放行失败（例如文章已被人工处理、状态已变化）时降级为待人工，而不是吞掉异常。
     */
    private boolean applyAutoApprove(ModerationTargetType targetType, Long targetId, AiModerationRecord record) {
        if (targetType != ModerationTargetType.ARTICLE) {
            log.warn("自动放行仅支持文章，实际类型：{}", targetType);
            return false;
        }
        try {
            if (!articleService.approve(targetId, SYSTEM_OPERATOR_ID)) {
                record.setAction(ModerationAction.PENDING_REVIEW.name());
                record.setActionReason("文字规则命中，须由管理员人工审核");
                record.setPriority(ModerationAction.PENDING_REVIEW.getPriority());
                record.setHandled(false);
                recordMapper.updateById(record);
                return false;
            }
            log.info(">>> 文章已由系统自动放行：articleId={}，依据审核记录 id={}", targetId, record.getId());
            return true;
        } catch (RuntimeException exception) {
            log.warn("自动放行失败，降级为待人工：articleId={}，原因={}", targetId, exception.getMessage());
            record.setAction(ModerationAction.PENDING_REVIEW.name());
            record.setActionReason("自动放行失败（" + exception.getMessage() + "），转人工审核");
            record.setPriority(ModerationAction.PENDING_REVIEW.getPriority());
            record.setHandled(false);
            recordMapper.updateById(record);
            return false;
        }
    }

    private AiModerationRecord saveRecord(
            ModerationTargetType targetType,
            Long targetId,
            String content,
            Long authorId,
            ModerationAgent.ModerationOutcome outcome,
            ActionPlan plan) {

        AiModerationRecord record = new AiModerationRecord();
        record.setTargetType(targetType.name());
        record.setTargetId(targetId);
        record.setTargetPreview(truncate(content, 200));
        record.setAuthorId(authorId);

        ModerationAssessment assessment = outcome.assessment();
        if (assessment == null) {
            // 分析失败：判断兜底为 REVIEW（"拿不准就交给人工"），五维记 0 分，
            // 语义由 action=ANALYSIS_FAILED 与 errorMessage 表达，不会被误当成"低风险可放行"。
            record.setDecision(ModerationDecision.REVIEW.name());
            record.setConfidence(0d);
            record.setHarmfulScore(0d);
            record.setPromotionScore(0d);
            record.setFraudScore(0d);
            record.setSpamScore(0d);
            record.setSensitiveScore(0d);
            record.setRiskScore(0d);
            record.setMaxDimension("none");
            record.setMaxDimensionScore(0d);
            record.setErrorMessage(outcome.errorMessage());
        } else {
            record.setDecision(assessment.decisionEnum().name());
            record.setConfidence(assessment.confidence());
            record.setHarmfulScore(assessment.harmfulScore());
            record.setPromotionScore(assessment.promotionScore());
            record.setFraudScore(assessment.fraudScore());
            record.setSpamScore(assessment.spamScore());
            record.setSensitiveScore(assessment.sensitiveScore());
            record.setHarmfulReason(assessment.harmfulReason());
            record.setPromotionReason(assessment.promotionReason());
            record.setFraudReason(assessment.fraudReason());
            record.setSpamReason(assessment.spamReason());
            record.setSensitiveReason(assessment.sensitiveReason());
            record.setSummary(assessment.summary());
            record.setRiskScore(assessment.maxScore());
            record.setMaxDimension(assessment.maxDimension());
            record.setMaxDimensionScore(assessment.maxScore());
        }

        record.setAction(plan.action().name());
        record.setActionReason(truncate(plan.reason(), 255));
        record.setPriority(plan.action().getPriority());
        // 不需要人工处理的动作（评论 PASS、系统已自动放行）直接标记为已处理，
        // 使管理台待处理列表（priority > 0 AND handled = 0）语义清晰。
        record.setHandled(!plan.action().needsHumanReview());
        record.setModel(outcome.model());
        record.setLatencyMs((int) outcome.latencyMillis());

        recordMapper.insert(record);
        return record;
    }

    // ==================== 管理台查询与人工反馈 ====================

    /**
     * 分页查询审核记录。
     *
     * <p>排序刻意是「优先级降序 + 时间降序」：高优先级的 REJECT 记录排在最前，
     * 管理员打开页面第一眼看到的就是最该处理的内容。
     */
    public Page<AiModerationRecord> pageRecords(long pageNum, long pageSize,
                                                String decision, String targetType, Boolean pendingOnly) {
        LambdaQueryWrapper<AiModerationRecord> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(decision)) {
            wrapper.eq(AiModerationRecord::getDecision, decision.trim().toUpperCase());
        }
        if (StringUtils.hasText(targetType)) {
            wrapper.eq(AiModerationRecord::getTargetType, targetType.trim().toUpperCase());
        }
        if (Boolean.TRUE.equals(pendingOnly)) {
            wrapper.gt(AiModerationRecord::getPriority, 0).eq(AiModerationRecord::getHandled, false);
        }
        wrapper.orderByDesc(AiModerationRecord::getPriority)
                .orderByDesc(AiModerationRecord::getCreateTime);
        return recordMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    /**
     * 提交人工反馈：管理员判断这次 AI 判断是否正确。
     *
     * <p>这是 M16 人工反馈闭环的数据来源，也是后续评测与提示词迭代的依据 ——
     * 没有它，AI 的效果只能靠离线样本评估，无法反映线上真实表现。
     */
    public void submitFeedback(Long recordId, String feedback, Long adminId) {
        String normalized = feedback == null ? "" : feedback.trim().toUpperCase();
        if (!AiModerationRecord.FEEDBACK_CORRECT.equals(normalized)
                && !AiModerationRecord.FEEDBACK_WRONG.equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "反馈取值只能是 CORRECT 或 WRONG");
        }
        AiModerationRecord record = requireRecord(recordId);
        record.setFeedback(normalized);
        record.setFeedbackBy(adminId);
        record.setFeedbackTime(LocalDateTime.now());
        recordMapper.updateById(record);
    }

    /** 标记该条待处理已由管理员处理完毕。 */
    public void markHandled(Long recordId, Long adminId) {
        AiModerationRecord record = requireRecord(recordId);
        record.setHandled(true);
        record.setHandledBy(adminId);
        record.setHandledTime(LocalDateTime.now());
        recordMapper.updateById(record);
    }

    private AiModerationRecord requireRecord(Long recordId) {
        AiModerationRecord record = recordMapper.selectById(recordId);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "审核记录不存在");
        }
        return record;
    }

    private String truncate(String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "…";
    }
}
