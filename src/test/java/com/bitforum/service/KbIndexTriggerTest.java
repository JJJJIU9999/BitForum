package com.bitforum.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.config.RabbitMQConfig;
import com.bitforum.entity.Article;
import com.bitforum.entity.ArticleAuditRecord;
import com.bitforum.mapper.ArticleAuditRecordMapper;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.message.KbIndexMessage;

/**
 * 知识库索引触发点测试（M15）。
 *
 * <p>验证文章可见性变化时确实发出了知识库索引消息：
 * 审核通过（进入已发布）、管理员删除（文章消失）。下架与删除共用同一个发送方法，
 * 监听器侧的移除分支由 {@code KbIndexMessageListenerTest} 覆盖。
 *
 * <p><b>刻意不加 {@code @Transactional}</b>：{@code sendKbIndexMessage} 是在
 * **事务提交后**才投递消息的（避免消费者在提交前读到旧状态）。若测试事务最终回滚，
 * afterCommit 回调根本不会执行，就验证不到真实行为。因此这里让事务真实提交，
 * 并在 {@link AfterEach} 中手工清理测试数据。
 */
@SpringBootTest
class KbIndexTriggerTest {

    private static final Long AUDITOR_ID = 88601L;
    private static final Long AUTHOR_ID = 88602L;
    private static final Long CATEGORY_ID = 1L;

    @Autowired
    private ArticleService articleService;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private ArticleAuditRecordMapper auditRecordMapper;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    private Long createdArticleId;

    @AfterEach
    void cleanUp() {
        if (createdArticleId == null) {
            return;
        }
        auditRecordMapper.delete(
                new LambdaQueryWrapper<ArticleAuditRecord>().eq(ArticleAuditRecord::getArticleId, createdArticleId));
        articleMapper.deleteById(createdArticleId);
        createdArticleId = null;
    }

    @Test
    void approvingArticleShouldSendKbIndexMessageAfterCommit() {
        Article article = insertArticle(ArticleService.STATUS_PENDING);

        articleService.approve(article.getId(), AUDITOR_ID);

        // 事务已提交，afterCommit 回调应已投递知识库索引消息
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.ARTICLE_EXCHANGE), eq(RabbitMQConfig.KB_INDEX_ROUTING_KEY), any(KbIndexMessage.class));
    }

    @Test
    void deletingArticleByAdminShouldSendKbIndexMessage() {
        Article article = insertArticle(ArticleService.STATUS_PUBLISHED);

        articleService.deleteByAdmin(article.getId());

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.ARTICLE_EXCHANGE), eq(RabbitMQConfig.KB_INDEX_ROUTING_KEY), any(KbIndexMessage.class));
        // 文章已被真删除，@AfterEach 的清理对已删除 id 是幂等的
    }

    private Article insertArticle(String status) {
        Article article = new Article();
        article.setTitle("知识库触发点测试");
        article.setContent("用于验证文章可见性变化时是否发出知识库索引消息。");
        article.setUserId(AUTHOR_ID);
        article.setCategoryId(CATEGORY_ID);
        article.setStatus(status);
        article.setCreateTime(LocalDateTime.now());
        article.setUpdateTime(LocalDateTime.now());
        articleMapper.insert(article);
        createdArticleId = article.getId();
        return article;
    }
}
