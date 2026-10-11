package com.bitforum.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.bitforum.ai.tool.ToolDtos.ActionResult;
import com.bitforum.service.ArticleService;
import com.bitforum.service.RedisService;
import com.bitforum.service.UserFollowService;
import com.bitforum.service.UserService;

/**
 * 用户互动工具：收藏、点赞、关注（M14）。
 *
 * 这些都是**写操作**，会真实改变站内数据。设计上的安全约束：
 * 1. 用户身份由模型传入当前登录用户 id，但每次都会做存在性校验，
 *    且底层 Service 会继续做归属与状态校验（例如只能收藏已发布文章）。
 * 2. 工具描述里明确要求"必须先与用户确认"，避免模型擅自替用户操作。
 * 3. 幂等语义与站内接口保持一致：重复点赞不会重复计数，重复收藏会被拒绝。
 */
@Component
public class UserInteractionTools {

    private static final Logger log = LoggerFactory.getLogger(UserInteractionTools.class);

    /** 点赞增加的热度权重，与 ArticleController 的点赞接口保持一致 */
    private static final double LIKE_HOT_SCORE = 3;

    private final ArticleService articleService;
    private final RedisService redisService;
    private final UserFollowService userFollowService;
    private final UserService userService;

    public UserInteractionTools(ArticleService articleService,
                                RedisService redisService,
                                UserFollowService userFollowService,
                                UserService userService) {
        this.articleService = articleService;
        this.redisService = redisService;
        this.userFollowService = userFollowService;
        this.userService = userService;
    }

    @Tool(description = """
            收藏一篇文章。这是写操作，会真的写入用户的收藏列表。
            仅在用户明确要求"收藏这篇""帮我收藏"时调用，且需先与用户确认是哪一篇。
            只能收藏已发布的文章；重复收藏会被拒绝。
            """)
    public ActionResult favoriteArticle(
            @ToolParam(description = "要收藏的文章 id，必须来自搜索结果") Long articleId,
            ToolContext toolContext) {

        if (currentUserId(toolContext) == null || articleId == null) {
            return ActionResult.fail("缺少用户身份或文章 id，无法收藏");
        }
        try {
            articleService.favoriteArticle(currentUserId(toolContext), articleId);
            log.info("AI 工具收藏文章：articleId={}, userId={}", articleId, currentUserId(toolContext));
            return ActionResult.ok("已收藏该文章", articleId);
        } catch (RuntimeException e) {
            log.warn("AI 工具收藏失败：userId={}, articleId={}, reason={}", currentUserId(toolContext), articleId, e.getMessage());
            return ActionResult.fail("收藏失败：" + e.getMessage());
        }
    }

    @Tool(description = """
            取消收藏一篇文章。这是写操作。
            仅在用户明确要求"取消收藏""不收藏了"时调用。
            """)
    public ActionResult unfavoriteArticle(
            @ToolParam(description = "要取消收藏的文章 id") Long articleId,
            ToolContext toolContext) {

        if (currentUserId(toolContext) == null || articleId == null) {
            return ActionResult.fail("缺少用户身份或文章 id，无法取消收藏");
        }
        try {
            articleService.unfavoriteArticle(currentUserId(toolContext), articleId);
            return ActionResult.ok("已取消收藏", articleId);
        } catch (RuntimeException e) {
            log.warn("AI 工具取消收藏失败：userId={}, articleId={}, reason={}", currentUserId(toolContext), articleId, e.getMessage());
            return ActionResult.fail("取消收藏失败：" + e.getMessage());
        }
    }

    @Tool(description = """
            给一篇文章点赞。这是写操作，会真的改变点赞数。
            仅在用户明确要求"点赞""赞一下"时调用。
            同一用户对同一篇文章只能点赞一次，重复点赞不会重复计数。
            """)
    public ActionResult likeArticle(
            @ToolParam(description = "要点赞的文章 id，必须来自搜索结果") Long articleId,
            ToolContext toolContext) {

        if (currentUserId(toolContext) == null || articleId == null) {
            return ActionResult.fail("缺少用户身份或文章 id，无法点赞");
        }
        // 与 ArticleController 一致：先校验文章存在且已发布
        if (articleService.findPublishedById(articleId) == null) {
            return ActionResult.fail("文章不存在或未发布，无法点赞");
        }
        try {
            boolean liked = redisService.like(articleId, currentUserId(toolContext));
            if (liked) {
                // 与站内点赞接口行为一致：成功点赞时提升热榜权重
                redisService.increaseHot(articleId, LIKE_HOT_SCORE);
            }
            Long count = redisService.getLikeCount(articleId);
            String message = liked
                    ? "点赞成功，当前点赞数：" + count
                    : "该用户已经点过赞了，当前点赞数：" + count;
            return ActionResult.ok(message, articleId);
        } catch (RuntimeException e) {
            log.warn("AI 工具点赞失败：userId={}, articleId={}, reason={}", currentUserId(toolContext), articleId, e.getMessage());
            return ActionResult.fail("点赞失败：" + e.getMessage());
        }
    }

    @Tool(description = """
            取消对一篇文章的点赞。这是写操作。
            仅在用户明确要求"取消点赞"时调用。
            """)
    public ActionResult unlikeArticle(
            @ToolParam(description = "要取消点赞的文章 id") Long articleId,
            ToolContext toolContext) {

        if (currentUserId(toolContext) == null || articleId == null) {
            return ActionResult.fail("缺少用户身份或文章 id，无法取消点赞");
        }
        try {
            redisService.unlike(articleId, currentUserId(toolContext));
            Long count = redisService.getLikeCount(articleId);
            return ActionResult.ok("已取消点赞，当前点赞数：" + count, articleId);
        } catch (RuntimeException e) {
            log.warn("AI 工具取消点赞失败：userId={}, articleId={}", currentUserId(toolContext), articleId, e);
            return ActionResult.fail("取消点赞失败：" + e.getMessage());
        }
    }

    @Tool(description = """
            关注一个用户。这是写操作。
            仅在用户明确要求"关注他""关注这个作者"时调用。
            需要目标用户的 id；如果是某篇文章的作者，可先通过文章详情或搜索结果的作者信息获取。
            不能关注自己。
            """)
    public ActionResult followUser(
            @ToolParam(description = "要关注的用户 id，不能是自己") Long targetUserId,
            ToolContext toolContext) {

        if (currentUserId(toolContext) == null || targetUserId == null) {
            return ActionResult.fail("缺少用户身份或目标用户 id，无法关注");
        }
        if (currentUserId(toolContext).equals(targetUserId)) {
            return ActionResult.fail("不能关注自己");
        }
        try {
            userFollowService.follow(currentUserId(toolContext), targetUserId);
            log.info("AI 工具关注用户：followerId={}, followingId={}", currentUserId(toolContext), targetUserId);
            return ActionResult.ok("已关注该用户", targetUserId);
        } catch (RuntimeException e) {
            log.warn("AI 工具关注失败：userId={}, targetUserId={}, reason={}", currentUserId(toolContext), targetUserId, e.getMessage());
            return ActionResult.fail("关注失败：" + e.getMessage());
        }
    }

    @Tool(description = """
            取消关注一个用户。这是写操作。
            仅在用户明确要求"取消关注"时调用。
            """)
    public ActionResult unfollowUser(
            @ToolParam(description = "要取消关注的用户 id") Long targetUserId,
            ToolContext toolContext) {

        if (currentUserId(toolContext) == null || targetUserId == null) {
            return ActionResult.fail("缺少用户身份或目标用户 id，无法取消关注");
        }
        try {
            userFollowService.unfollow(currentUserId(toolContext), targetUserId);
            return ActionResult.ok("已取消关注", targetUserId);
        } catch (RuntimeException e) {
            log.warn("AI 工具取消关注失败：userId={}, targetUserId={}, reason={}", currentUserId(toolContext), targetUserId, e.getMessage());
            return ActionResult.fail("取消关注失败：" + e.getMessage());
        }
    }

    @Tool(description = """
            查询某个用户的关注与粉丝统计。当用户询问"他有多少粉丝""我关注了多少人"时使用。
            返回关注数、粉丝数，以及当前登录用户是否已关注该用户。
            """)
    public FollowStatsView getFollowStats(
            @ToolParam(description = "要查询的用户 id") Long targetUserId,
            ToolContext toolContext) {

        if (targetUserId == null) {
            throw new IllegalArgumentException("用户 id 不能为空");
        }
        // 当前登录用户同样从上下文取，用于判断"是否已关注"
        var stats = userFollowService.getStats(targetUserId, currentUserId(toolContext));
        String username = null;
        try {
            username = userService.publicDisplayName(targetUserId);
        } catch (RuntimeException e) {
            log.debug("查询用户名失败：userId={}", targetUserId, e);
        }
        return new FollowStatsView(targetUserId, username,
                stats.getFollowingCount(), stats.getFollowerCount(), stats.getFollowedByCurrentUser());
    }

    /** 关注统计视图：不直接复用 DTO，避免把内部字段暴露给模型 */
    public record FollowStatsView(Long userId,
                                  String username,
                                  Long followingCount,
                                  Long followerCount,
                                  Boolean followedByCurrentUser) {
    }

    /**
     * 从 ToolContext 取当前登录用户 id。
     *
     * 身份由应用注入（见 QaAgent），刻意不暴露为工具参数：
     * 否则模型会向用户索要"你的用户 id"，并可能传错身份。
     * 这里与 ArticleTools.currentUserId 共用同一实现，保证行为一致。
     */
    private Long currentUserId(ToolContext toolContext) {
        return ArticleTools.currentUserId(toolContext);
    }
}
