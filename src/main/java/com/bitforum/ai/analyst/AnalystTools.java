package com.bitforum.ai.analyst;

import org.springframework.ai.tool.annotation.Tool;

import com.bitforum.dto.AdminDashboardSummaryResponse;

/**
 * 运营看板工具（M17）。
 *
 * <p>把 M6 的 {@code AdminDashboardService} 统计暴露给运营分析 Agent。
 * 与 {@code ToolRegistry} 的按类型装配是同一原则：**运营 Agent 只拿到只读的统计工具**，
 * 没有任何写工具（不能发文章、不能删评论），因为运营分析的职责只是"读数据、写结论"。
 *
 * <p><b>为什么刻意不做成单例 Bean、而是每次请求新建</b>：
 * 它必须绑定"本次请求取到的那一份统计快照"。如果做成单例并在内部缓存统计，
 * 模型看到的数字与报告落库的快照可能来自两次不同的查询，那份报告就无法对账了。
 *
 * <p><b>为什么是一个工具返回完整统计、而不是拆成七八个小工具</b>：
 * T10 实测（findings.md 6.14）表明完整统计序列化后只有 699 字符（约 300 token），
 * 模型一次就能读完，且引用的数字 10/10 与 10/12 命中真实值（未命中的 2 个是百分比换算）。
 * 拆成多个工具只会增加模型的调用轮次与出错机会，并不会省下多少 token。
 */
public class AnalystTools {

    private final AdminDashboardSummaryResponse summary;

    /** 本次分析中工具被调用的次数，用于测试与日志（模型不调用工具说明提示词或工具描述有问题）。 */
    private int callCount = 0;

    public AnalystTools(AdminDashboardSummaryResponse summary) {
        this.summary = summary;
    }

    public int callCount() {
        return callCount;
    }

    @Tool(description = """
            获取社区运营数据看板的完整汇总统计，包含：
            用户（总数、普通用户数、管理员数、启用数、禁用数）、
            文章（总数、草稿数、待审核数、已发布数、已驳回数、已下架数、今日新增、近 7 天新增）、
            评论（总数、今日新增、近 7 天新增）、
            板块（总数、启用数、禁用数）、收藏总数、
            通知（总数、未读数）、
            举报（总数、待处理数、已处理数、已驳回数、今日新增、近 7 天新增）、
            以及热门文章排行（文章 id、标题、热度分、浏览量、点赞数）。
            需要了解社区现状、撰写运营分析或回答任何站内统计问题时调用本工具。
            """)
    public AdminDashboardSummaryResponse getDashboardSummary() {
        callCount++;
        return summary;
    }
}
