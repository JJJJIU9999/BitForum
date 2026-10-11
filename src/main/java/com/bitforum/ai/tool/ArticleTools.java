package com.bitforum.ai.tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.ai.tool.ToolDtos.ActionResult;
import com.bitforum.ai.tool.ToolDtos.ArticleBrief;
import com.bitforum.ai.tool.ToolDtos.ArticleDetail;
import com.bitforum.ai.tool.ToolDtos.HotArticleBrief;
import com.bitforum.ai.tool.ToolDtos.PagedResult;
import com.bitforum.entity.Article;
import com.bitforum.entity.Category;
import com.bitforum.service.ArticleService;
import com.bitforum.service.CategoryService;
import com.bitforum.service.RedisService;
import com.bitforum.service.UserService;

/**
 * 文章相关工具（M14）。
 *
 * 设计要点：
 * 1. 只做参数适配与结果裁剪，业务逻辑一律复用既有 Service，不重复写 SQL。
 * 2. 返回给模型的是精简视图（见 ToolDtos），正文按需截断，避免上下文与成本失控。
 * 3. 涉及写操作的用户身份由模型传入当前登录用户 id，但权限仍由既有 Service 校验
 *    （例如"只能收藏已发布文章"），工具层不做也不应绕过这类校验。
 */
@Component
public class ArticleTools {

    private static final Logger log = LoggerFactory.getLogger(ArticleTools.class);

    /** 返回给模型的正文上限：过长的正文会迅速消耗上下文并推高成本 */
    private static final int CONTENT_EXCERPT_LIMIT = 800;
    /** 单次搜索最多返回条数，防止模型一次拉取过多结果 */
    private static final int MAX_PAGE_SIZE = 20;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ArticleService articleService;
    private final CategoryService categoryService;
    private final RedisService redisService;
    private final UserService userService;

    public ArticleTools(ArticleService articleService,
                        CategoryService categoryService,
                        RedisService redisService,
                        UserService userService) {
        this.articleService = articleService;
        this.categoryService = categoryService;
        this.redisService = redisService;
        this.userService = userService;
    }

    @Tool(description = """
            按关键词搜索站内已发布的文章。当用户询问站内有哪些相关文章、
            某个技术主题的讨论、或需要查找资料时使用。
            只返回已发布（PUBLISHED）的文章，草稿与待审核内容不会出现。
            返回字段包含文章 id、标题、作者、板块、浏览与点赞数，可用于后续生成引用。
            """)
    public PagedResult<ArticleBrief> searchArticles(
            @ToolParam(description = "搜索关键词，可为空；为空时返回最新文章") String keyword,
            @ToolParam(description = "板块 id，可为空；不确定时不要传") Long categoryId,
            @ToolParam(description = "页码，从 1 开始；不传默认 1") Integer pageNum,
            @ToolParam(description = "每页条数，最大 20；不传默认 5") Integer pageSize) {

        int page = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 5 : Math.min(pageSize, MAX_PAGE_SIZE);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();

        Page<Article> result = articleService.searchPublishedArticles(normalizedKeyword, categoryId, page, size);
        List<ArticleBrief> items = result.getRecords().stream().map(this::toBrief).toList();
        return new PagedResult<>(result.getTotal(), page, size, page * size < result.getTotal(), items);
    }

    @Tool(description = """
            获取某篇文章的完整信息，包含正文摘要。
            在用户追问某篇文章的具体内容、或已从搜索结果拿到文章 id 需要深入阅读时使用。
            正文超过 800 字会被截断，并在摘要末尾标注已截断。
            只能获取已发布的文章；草稿、待审核、已下架的文章会返回提示。
            """)
    public ArticleDetail getArticleDetail(
            @ToolParam(description = "文章 id，必须来自搜索结果，不要凭空编造") Long articleId) {

        if (articleId == null) {
            throw new IllegalArgumentException("文章 id 不能为空");
        }
        Article article = articleService.findPublishedById(articleId);
        if (article == null) {
            throw new IllegalArgumentException("文章不存在或未发布，id=" + articleId);
        }
        return toDetail(article);
    }

    @Tool(description = """
            获取站内热门文章排行榜，按热度分数从高到低。
            当用户询问"最近什么话题受欢迎""热门文章""大家在讨论什么"时使用。
            热度由浏览量、点赞等行为综合计算。
            """)
    public List<HotArticleBrief> getHotArticles(
            @ToolParam(description = "返回条数，最大 10；不传默认 5") Integer topN) {

        int limit = topN == null || topN < 1 ? 5 : Math.min(topN, 10);
        return redisService.getHotList(limit).stream()
                .filter(tuple -> tuple.getValue() != null)
                .map(tuple -> new HotArticleBrief(
                        parseLong(tuple.getValue()),
                        titleOf(tuple.getValue()),
                        tuple.getScore()))
                .toList();
    }

    @Tool(description = """
            列出站内所有已启用的板块（分类）。当用户询问有哪些板块、某个板块下有什么内容，
            或需要按板块搜索前先确认板块 id 时使用。
            """)
    public List<ToolDtos.CategoryBrief> listCategories() {
        return categoryService.listEnabled().stream()
                .map(category -> new ToolDtos.CategoryBrief(
                        category.getId(), category.getName(), category.getDescription()))
                .toList();
    }

    @Tool(description = """
            以草稿形式创建一篇新文章。这是写操作，会把内容真的写入站内。
            仅在用户明确要求"帮我发一篇""保存成草稿"时调用，且必须与用户确认过标题与内容。
            创建后文章为草稿状态，作者可在站内继续编辑并提交审核。不会自动提交审核。
            作者身份由系统自动确定，无需也无法指定。
            """)
    public ActionResult createDraftArticle(
            @ToolParam(description = "文章标题，不超过 50 字") String title,
            @ToolParam(description = "文章正文内容") String content,
            @ToolParam(description = "板块 id，必须是 listCategories 返回的启用板块 id") Long categoryId,
            ToolContext toolContext) {

        Long userId = currentUserId(toolContext);
        if (userId == null) {
            return ActionResult.fail("缺少用户身份，无法创建文章");
        }
        if (title == null || title.isBlank()) {
            return ActionResult.fail("标题不能为空");
        }
        if (content == null || content.isBlank()) {
            return ActionResult.fail("正文内容不能为空");
        }
        try {
            Article created = articleService.saveDraft(title.trim(), content, categoryId, userId);
            log.info("AI 工具创建草稿：articleId={}, userId={}", created.getId(), userId);
            return ActionResult.ok("草稿已创建，状态为草稿，尚未提交审核。标题：" + created.getTitle(),
                    created.getId());
        } catch (RuntimeException e) {
            // 板块不存在或已禁用等原因，交由模型转述给用户
            log.warn("AI 工具创建草稿失败：userId={}, reason={}", userId, e.getMessage());
            return ActionResult.fail("创建草稿失败：" + e.getMessage());
        }
    }

    /**
     * 从 ToolContext 取当前登录用户 id。
     *
     * 用户身份由应用在调用模型时注入（见 QaAgent），刻意不作为工具参数暴露给模型：
     * 让模型传 userId 既会逼它向用户索要 id，也给了它传错身份的机会。
     */
    static Long currentUserId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(AgentContextKeys.USER_ID);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    // ==================== 内部转换 ====================

    private ArticleBrief toBrief(Article article) {
        return new ArticleBrief(
                article.getId(),
                article.getTitle(),
                authorName(article.getUserId()),
                categoryName(article.getCategoryId()),
                article.getStatus(),
                article.getViewCount(),
                article.getLikeCount(),
                formatTime(article.getCreateTime()));
    }

    private ArticleDetail toDetail(Article article) {
        String content = article.getContent() == null ? "" : article.getContent();
        boolean truncated = content.length() > CONTENT_EXCERPT_LIMIT;
        String excerpt = truncated ? content.substring(0, CONTENT_EXCERPT_LIMIT) + "……（正文已截断）" : content;
        return new ArticleDetail(
                article.getId(),
                article.getTitle(),
                authorName(article.getUserId()),
                categoryName(article.getCategoryId()),
                article.getStatus(),
                article.getViewCount(),
                article.getLikeCount(),
                formatTime(article.getCreateTime()),
                excerpt);
    }

    /** 作者名查不到时回退为"未知用户"，而不是抛出异常影响整次工具调用 */
    private String authorName(Long userId) {
        if (userId == null) {
            return "未知用户";
        }
        try {
            String name = userService.publicDisplayName(userId);
            return name == null ? "未知用户" : name;
        } catch (RuntimeException e) {
            log.debug("查询作者名失败：userId={}", userId, e);
            return "未知用户";
        }
    }

    private String categoryName(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        try {
            Category category = categoryService.findById(categoryId);
            return category == null ? null : category.getName();
        } catch (RuntimeException e) {
            log.debug("查询板块名失败：categoryId={}", categoryId, e);
            return null;
        }
    }

    /** 热榜 key 的取值格式详见 RedisService.increaseHot 的 key 约定 */
    private Long parseLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 热榜 ZSet 的成员是文章 id；标题按需查库，查不到时返回占位文案 */
    private String titleOf(String value) {
        Long articleId = parseLong(value);
        if (articleId == null) {
            return "未知文章";
        }
        try {
            Article article = articleService.findPublishedById(articleId);
            return article == null ? "（该文章已不可见）" : article.getTitle();
        } catch (RuntimeException e) {
            log.debug("查询热榜文章标题失败：articleId={}", articleId, e);
            return "（标题查询失败）";
        }
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FORMAT);
    }
}
