package com.bitforum.ai.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.entity.AiModerationRecord;
import com.bitforum.ai.mapper.AiModerationRecordMapper;
import com.bitforum.entity.Article;
import com.bitforum.entity.Comment;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CommentMapper;
import com.bitforum.mapper.NotificationMapper;
import com.bitforum.entity.Notification;
import com.bitforum.service.ArticleService;
import com.bitforum.service.CommentService;

/**
 * 审核自动联动端到端测试（M16）。
 *
 * <p>验证真实链路：**提交文章 / 发布评论 → RabbitMQ → 监听器 → AI 分析 → 落库**，
 * 并顺带验证两条安全约定：
 *
 * <ul>
 *   <li>默认配置下（自动放行开关关闭）文章**不会**被自动放行，仍停在待审核状态；</li>
 *   <li>审核结论与系统动作分别落库，且动作不会是"自动驳回"。</li>
 * </ul>
 *
 * <p>默认不执行（真实 AI 调用 + 真实 MQ），仅在 DEEPSEEK_CHAT_ENABLED=true 时运行。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_CHAT_ENABLED", matches = "true")
class ModerationAutoReviewIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ModerationAutoReviewIntegrationTest.class);

    private static final Long AUTHOR_ID = 88901L;
    private static final Long COMMENTER_ID = 88902L;
    private static final Long CATEGORY_ID = 1L;
    private static final Duration TIMEOUT = Duration.ofSeconds(40);

    @Autowired
    private ArticleService articleService;
    @Autowired
    private CommentService commentService;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private NotificationMapper notificationMapper;
    @Autowired
    private AiModerationRecordMapper recordMapper;

    private final List<Long> createdArticleIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long articleId : createdArticleIds) {
            recordMapper.delete(new LambdaQueryWrapper<AiModerationRecord>()
                    .eq(AiModerationRecord::getTargetType, AiModerationRecord.TARGET_ARTICLE)
                    .eq(AiModerationRecord::getTargetId, articleId));
            List<Comment> comments = commentMapper.selectList(
                    new LambdaQueryWrapper<Comment>().eq(Comment::getArticleId, articleId));
            for (Comment comment : comments) {
                recordMapper.delete(new LambdaQueryWrapper<AiModerationRecord>()
                        .eq(AiModerationRecord::getTargetType, AiModerationRecord.TARGET_COMMENT)
                        .eq(AiModerationRecord::getTargetId, comment.getId()));
            }
            commentMapper.delete(new LambdaQueryWrapper<Comment>().eq(Comment::getArticleId, articleId));
            articleMapper.deleteById(articleId);
        }
        createdArticleIds.clear();
        notificationMapper.delete(new LambdaQueryWrapper<Notification>().eq(Notification::getReceiverId, COMMENTER_ID));
    }

    @Test
    void submittingArticleShouldBeReviewedAsynchronouslyWithoutAutoApproval() {
        Article article = insertArticle(ArticleService.STATUS_DRAFT,
                "关于缓存穿透的讨论",
                "缓存穿透指查询一个数据库里也不存在的记录，常见对策是缓存空值或使用布隆过滤器。");

        articleService.submit(AUTHOR_ID, article.getId());

        AiModerationRecord record = awaitRecord(AiModerationRecord.TARGET_ARTICLE, article.getId());
        assertNotNull(record, "提交审核后应产生 AI 审核记录（超时未出现）");

        assertNotNull(record.getDecision(), "应记录 AI 判断");
        assertNotNull(record.getAction(), "应记录系统动作");
        assertNotNull(record.getRiskScore(), "应记录由 Java 计算的综合风险分");

        // 默认开关关闭：即使 AI 判 PASS，文章也必须留在待审核状态等人工
        Article reloaded = articleMapper.selectById(article.getId());
        assertEquals(ArticleService.STATUS_PENDING, reloaded.getStatus(),
                "默认配置下不得自动放行，文章应保持待审核");

        log.info(">>> 文章异步审核：decision={}，action={}，riskScore={}，最高维度={}",
                record.getDecision(), record.getAction(), record.getRiskScore(), record.getMaxDimension());
    }

    @Test
    void publishingCommentShouldBeReviewedAsynchronously() {
        Article article = insertArticle(ArticleService.STATUS_PUBLISHED,
                "已发布文章",
                "这是一篇已发布的文章，用于测试评论审核。");

        boolean published = commentService.publish(COMMENTER_ID, article.getId(),
                "这个方案我试过，注意加锁的粒度，另外建议补一个失败回滚。");
        assertTrue(published, "评论应发布成功");

        List<Comment> comments =
                commentMapper.selectList(new LambdaQueryWrapper<Comment>().eq(Comment::getArticleId, article.getId()));
        assertEquals(1, comments.size(), "评论已入库");

        AiModerationRecord record = awaitRecord(AiModerationRecord.TARGET_COMMENT, comments.get(0).getId());
        assertNotNull(record, "评论发布后应产生 AI 审核记录（超时未出现）");

        // 评论发布即公开，AI 的动作只能是"记录/提醒"，绝不是删除
        assertTrue(
                List.of(ModerationAction.NO_ACTION.name(), ModerationAction.PENDING_REVIEW.name(),
                        ModerationAction.HIGH_PRIORITY_REVIEW.name(), ModerationAction.ANALYSIS_FAILED.name())
                        .contains(record.getAction()),
                "评论审核动作不应是任何自动删改动作，实际：" + record.getAction());

        log.info(">>> 评论异步审核：decision={}，action={}，riskScore={}",
                record.getDecision(), record.getAction(), record.getRiskScore());
    }

    private AiModerationRecord awaitRecord(String targetType, Long targetId) {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            AiModerationRecord record = recordMapper.selectOne(new LambdaQueryWrapper<AiModerationRecord>()
                    .eq(AiModerationRecord::getTargetType, targetType)
                    .eq(AiModerationRecord::getTargetId, targetId)
                    .orderByDesc(AiModerationRecord::getId)
                    .last("LIMIT 1"));
            if (record != null) {
                return record;
            }
            sleep();
        }
        return null;
    }

    private void sleep() {
        try {
            Thread.sleep(300L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待异步审核时被中断", exception);
        }
    }

    private Article insertArticle(String status, String title, String content) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent(content);
        article.setUserId(AUTHOR_ID);
        article.setCategoryId(CATEGORY_ID);
        article.setStatus(status);
        article.setCreateTime(LocalDateTime.now());
        article.setUpdateTime(LocalDateTime.now());
        articleMapper.insert(article);
        createdArticleIds.add(article.getId());
        return article;
    }
}
