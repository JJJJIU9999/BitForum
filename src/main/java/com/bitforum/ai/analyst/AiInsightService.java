package com.bitforum.ai.analyst;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.entity.AiInsightReport;
import com.bitforum.ai.mapper.AiInsightReportMapper;

/**
 * 运营洞察报告的存取（M17）。
 *
 * <p><b>与"何时生成"解耦</b>：本类只负责把 {@link AnalystAgent} 的产出存下来、把最新的取出来。
 * 至于是管理员点按钮同步生成、还是异步生成后落库、还是定时生成，
 * 只是调用方不同，本类完全不用改（见 m17-decision-brief.md 的 Q4）。
 *
 * <p><b>成功与失败都落库</b>：生成失败时 {@code status=FAILED} 且保留 {@code data_snapshot} ——
 * 统计是本地聚合的，与 AI 是否可用无关。这样管理员能看到"数据取到了，是模型这一步失败了"，
 * 而不是面对一片空白；后续重试也有依据。
 */
@Service
public class AiInsightService {

    private static final Logger log = LoggerFactory.getLogger(AiInsightService.class);

    /** 历史列表一次最多返回多少条，避免管理台一次拉出全部历史。 */
    private static final int MAX_HISTORY = 50;

    private final AiInsightReportMapper reportMapper;

    public AiInsightService(AiInsightReportMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    /**
     * 保存一次生成结果（成功或失败都存）。
     *
     * @param triggerType 触发方式，取 {@link AiInsightReport#TRIGGER_MANUAL} 等常量
     * @param requestedBy 触发人（管理员 id）；定时任务触发时可为 null
     */
    public AiInsightReport save(AnalystAgent.InsightOutcome outcome, String triggerType, Long requestedBy) {
        AiInsightReport report = new AiInsightReport();
        report.setTriggerType(triggerType == null ? AiInsightReport.TRIGGER_MANUAL : triggerType);
        report.setRequestedBy(requestedBy);
        applyOutcome(report, outcome);
        reportMapper.insert(report);
        logOutcome(report, outcome);
        return report;
    }

    /**
     * 先落一条 PENDING 记录（"已受理，正在生成"）。
     *
     * <p><b>异步生成必须先把这条记录写进去</b>：否则从管理员点下按钮到生成完成之间，
     * 前端既查不到"正在生成"、也看不到失败原因，只能干等或反复点击。
     * 这条记录同时是**防重复触发**的依据，也是服务重启后判断"哪次生成没跑完"的线索。
     */
    public AiInsightReport createPending(String triggerType, Long requestedBy) {
        AiInsightReport report = new AiInsightReport();
        report.setStatus(AiInsightReport.STATUS_PENDING);
        report.setTriggerType(triggerType == null ? AiInsightReport.TRIGGER_MANUAL : triggerType);
        report.setRequestedBy(requestedBy);
        report.setDataTime(LocalDateTime.now());
        reportMapper.insert(report);
        log.info("运营洞察已受理（PENDING）：id={}，触发人={}", report.getId(), requestedBy);
        return report;
    }

    /** 把异步生成的结果写回那条 PENDING 记录。 */
    public void complete(Long reportId, AnalystAgent.InsightOutcome outcome) {
        AiInsightReport report = new AiInsightReport();
        report.setId(reportId);
        applyOutcome(report, outcome);
        reportMapper.updateById(report);
        logOutcome(report, outcome);
    }

    /**
     * 生成过程中抛出异常时的兜底。
     *
     * <p>{@link AnalystAgent#analyze()} 本身不抛异常（失败也返回降级结果），
     * 这里是防御性处理：万一有未预料的异常，也不能让那条记录永远停在 PENDING。
     */
    public void markFailed(Long reportId, String errorMessage) {
        AiInsightReport report = new AiInsightReport();
        report.setId(reportId);
        report.setStatus(AiInsightReport.STATUS_FAILED);
        report.setErrorMessage(abbreviate(errorMessage));
        reportMapper.updateById(report);
        log.warn("运营洞察生成异常已记录：id={}，原因={}", reportId, errorMessage);
    }

    /** 当前正在生成的那一条（前端据此决定"继续轮询"还是"已经结束"）。 */
    public Optional<AiInsightReport> pending() {
        return Optional.ofNullable(reportMapper.selectOne(new LambdaQueryWrapper<AiInsightReport>()
                .eq(AiInsightReport::getStatus, AiInsightReport.STATUS_PENDING)
                .orderByAsc(AiInsightReport::getId)
                .last("LIMIT 1")));
    }

    /** 把一次生成结果的各个字段套到报告上（成功与失败共用）。 */
    private void applyOutcome(AiInsightReport report, AnalystAgent.InsightOutcome outcome) {
        report.setStatus(outcome.degraded() ? AiInsightReport.STATUS_FAILED : AiInsightReport.STATUS_SUCCESS);
        report.setContent(outcome.content());
        report.setDataSnapshot(outcome.dataSnapshot());
        report.setDataTime(outcome.dataTime());
        report.setModel(outcome.model());
        report.setPromptTokens(outcome.promptTokens());
        report.setCompletionTokens(outcome.completionTokens());
        report.setTotalTokens(outcome.totalTokens());
        report.setLatencyMs((int) outcome.latencyMillis());
        report.setErrorMessage(outcome.errorMessage());
    }

    private void logOutcome(AiInsightReport report, AnalystAgent.InsightOutcome outcome) {
        if (outcome.degraded()) {
            log.warn("运营洞察生成失败已记录：id={}，原因={}", report.getId(), outcome.errorMessage());
        } else {
            log.info("运营洞察已保存：id={}，耗时 {} ms，模型调用统计工具 {} 次",
                    report.getId(), outcome.latencyMillis(), outcome.toolCallCount());
        }
    }

    /** error_message 列是 varchar(500)，超长会写入失败，这里统一截断。 */
    private String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    /**
     * 最新一份**成功**的报告，供看板卡片展示。
     *
     * <p>刻意只取成功的：失败的记录要留痕（供排查），但不应显示成"当前运营洞察"，
     * 否则管理员会看到一份没有正文的旧失败记录。
     */
    public Optional<AiInsightReport> latestSuccess() {
        return Optional.ofNullable(reportMapper.selectOne(new LambdaQueryWrapper<AiInsightReport>()
                .eq(AiInsightReport::getStatus, AiInsightReport.STATUS_SUCCESS)
                .orderByDesc(AiInsightReport::getCreateTime)
                .orderByDesc(AiInsightReport::getId)
                // 加 LIMIT 1 是为了让 selectOne 稳定：同一秒内可能有多条，
                // 不加限制时 selectOne 在结果多于一条时会抛异常
                .last("LIMIT 1")));
    }

    /** 最近若干份报告（含失败的），用于管理台历史列表。 */
    public List<AiInsightReport> recent(int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_HISTORY));
        return reportMapper.selectList(new LambdaQueryWrapper<AiInsightReport>()
                .orderByDesc(AiInsightReport::getCreateTime)
                .orderByDesc(AiInsightReport::getId)
                .last("LIMIT " + bounded));
    }

    /**
     * 是否已有报告在生成中。
     *
     * <p>异步触发时前端要靠它决定"继续转圈还是已经好了"；
     * 同时它也是防止管理员连点、重复触发的前提。
     */
    public boolean hasPending() {
        Long count = reportMapper.selectCount(new LambdaQueryWrapper<AiInsightReport>()
                .eq(AiInsightReport::getStatus, AiInsightReport.STATUS_PENDING));
        return count != null && count > 0;
    }
}
