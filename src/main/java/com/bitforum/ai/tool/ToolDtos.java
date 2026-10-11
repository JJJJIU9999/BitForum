package com.bitforum.ai.tool;

import java.util.List;

/**
 * 工具返回给模型的数据视图（M14）。
 *
 * 设计原则：只暴露模型回答问题所需的最小字段。
 * 不要把完整实体直接丢给模型 —— 例如 Article.content 可能是几万字的正文，
 * 会迅速吃光上下文并推高成本；列表场景只给摘要，正文单独由详情工具按需提供。
 */
public final class ToolDtos {

    private ToolDtos() {
    }

    /** 文章摘要：用于搜索结果与列表类回答 */
    public record ArticleBrief(Long id,
                               String title,
                               String authorName,
                               String categoryName,
                               String status,
                               Integer viewCount,
                               Integer likeCount,
                               String createTime) {
    }

    /** 文章详情：正文已截断，避免单次调用超长 */
    public record ArticleDetail(Long id,
                                String title,
                                String authorName,
                                String categoryName,
                                String status,
                                Integer viewCount,
                                Integer likeCount,
                                String createTime,
                                String contentExcerpt) {
    }

    /** 板块信息 */
    public record CategoryBrief(Long id, String name, String description) {
    }

    /** 热榜条目 */
    public record HotArticleBrief(Long id, String title, Double hotScore) {
    }

    /** 分页结果：把 MyBatis-Plus 的 Page 转成模型易读的紧凑结构 */
    public record PagedResult<T>(long total,
                                 long pageNum,
                                 long pageSize,
                                 boolean hasMore,
                                 List<T> items) {
    }

    /** 写操作结果：明确告知模型操作是否成功、对象是谁，便于它组织回答 */
    public record ActionResult(boolean success, String message, Long targetId) {

        public static ActionResult ok(String message, Long targetId) {
            return new ActionResult(true, message, targetId);
        }

        public static ActionResult fail(String message) {
            return new ActionResult(false, message, null);
        }
    }
}
