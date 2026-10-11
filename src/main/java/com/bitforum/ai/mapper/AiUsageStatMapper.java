package com.bitforum.ai.mapper;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitforum.ai.dto.AiUsageDtos;
import com.bitforum.ai.entity.AiUsageStat;

/**
 * AI 用量统计 Mapper（M18）。
 *
 * <p>除了基础的增查，这里用注解 SQL 写了四个**聚合查询** —— 放在 Mapper 而不是 Service 里，
 * 是因为它们都是单条 GROUP BY 语句，用 Java 把明细捞回来再聚合会让内存随调用量线性增长。
 *
 * <p>聚合条件统一是 {@code stat_date >= #{from}}：用量是"最近 N 天"的视角，
 * 用日期列过滤可以直接命中索引。
 */
@Mapper
public interface AiUsageStatMapper extends BaseMapper<AiUsageStat> {

    /** 区间内的总量与平均耗时。 */
    @Select("""
            SELECT COUNT(*) AS calls,
                   COALESCE(SUM(prompt_tokens), 0) AS promptTokens,
                   COALESCE(SUM(completion_tokens), 0) AS completionTokens,
                   COALESCE(SUM(total_tokens), 0) AS totalTokens,
                   COALESCE(SUM(estimated_cost), 0) AS cost,
                   COALESCE(AVG(latency_ms), 0) AS averageLatencyMs,
                   COALESCE(SUM(CASE WHEN result <> 'SUCCESS' THEN 1 ELSE 0 END), 0) AS abnormalCalls
            FROM ai_usage_stat
            WHERE stat_date >= #{from}""")
    AiUsageDtos.Totals selectTotals(@Param("from") LocalDate from);

    /** 按天汇总（趋势）。 */
    @Select("""
            SELECT stat_date AS statDate,
                   COUNT(*) AS calls,
                   COALESCE(SUM(total_tokens), 0) AS totalTokens,
                   COALESCE(SUM(estimated_cost), 0) AS cost
            FROM ai_usage_stat
            WHERE stat_date >= #{from}
            GROUP BY stat_date
            ORDER BY stat_date""")
    List<AiUsageDtos.DailyItem> selectDaily(@Param("from") LocalDate from);

    /** 按 Agent 汇总（谁最贵）。 */
    @Select("""
            SELECT agent_type AS agentType,
                   COUNT(*) AS calls,
                   COALESCE(SUM(total_tokens), 0) AS totalTokens,
                   COALESCE(SUM(estimated_cost), 0) AS cost
            FROM ai_usage_stat
            WHERE stat_date >= #{from}
            GROUP BY agent_type
            ORDER BY SUM(total_tokens) DESC""")
    List<AiUsageDtos.AgentItem> selectByAgent(@Param("from") LocalDate from);

    /** 消耗最高的若干用户（按 token 排序）。 */
    @Select("""
            SELECT user_id AS userId,
                   COUNT(*) AS calls,
                   COALESCE(SUM(total_tokens), 0) AS totalTokens,
                   COALESCE(SUM(estimated_cost), 0) AS cost
            FROM ai_usage_stat
            WHERE stat_date >= #{from} AND user_id IS NOT NULL
            GROUP BY user_id
            ORDER BY SUM(total_tokens) DESC
            LIMIT #{limit}""")
    List<AiUsageDtos.UserItem> selectTopUsers(@Param("from") LocalDate from, @Param("limit") int limit);

    /**
     * 某个用户**某一天**的合计用量（M18 预算闸门用）。
     *
     * <p>走 `idx_aius_user_date` 索引，是"每次 AI 调用前问一句今天用了多少"这种高频只读查询，
     * 因此刻意不做缓存：缓存会引入"刚花的额度看不到"的窗口，而这条查询本身很轻。
     */
    @Select("""
            SELECT COALESCE(SUM(total_tokens), 0) AS totalTokens,
                   COALESCE(SUM(estimated_cost), 0) AS cost
            FROM ai_usage_stat
            WHERE user_id = #{userId} AND stat_date = #{statDate}""")
    AiUsageDtos.UserUsage selectUserDaily(@Param("userId") Long userId,
                                          @Param("statDate") LocalDate statDate);
}
