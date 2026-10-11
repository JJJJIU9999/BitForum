package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.config.RabbitMQConfig;
import com.bitforum.entity.Article;
import com.bitforum.entity.ArticleAuditRecord;
import com.bitforum.entity.ArticleFavorite;
import com.bitforum.entity.Category;
import com.bitforum.entity.Comment;
import com.bitforum.entity.Notification;
import com.bitforum.mapper.ArticleAuditRecordMapper;
import com.bitforum.mapper.ArticleFavoriteMapper;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CategoryMapper;
import com.bitforum.mapper.CommentMapper;
import com.bitforum.mapper.NotificationMapper;
import com.bitforum.message.ArticlePublishMessage;

// 启动完整 Spring 容器，让 ArticleService、Mapper、事务等组件按真实项目方式协作
@SpringBootTest
// 测试结束后自动回滚数据库，不把测试文章留在真实表里。
@Transactional
public class ArticleServiceTest {
    @Autowired
    private ArticleService articleService;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private ArticleAuditRecordMapper articleAuditRecordMapper;
    @MockitoSpyBean
    private ArticleFavoriteMapper articleFavoriteMapper;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private NotificationMapper notificationMapper;
    @Autowired
    private CategoryService categoryService;
    // 不真的连接 RabbitMQ，只检查代码有没有调用“发送消息”。
    @MockitoBean
    private RabbitTemplate rabbitTemplate;
    @MockitoBean
    private RedisService redisService;

    @Test
    void publishShouldSaveArticleAsPendingWithoutSendingMessage() {
        Long userId = 10001L;
        String title = "测试文章-" + UUID.randomUUID();
        String content = "这是一篇测试文章";
        Long categoryId = defaultCategoryId();

        articleService.publish(title, content, categoryId, userId);

        Article article = findByTitle(title);

        assertNotNull(article);
        assertEquals(content, article.getContent());
        assertEquals(userId, article.getUserId());
        assertEquals(categoryId, article.getCategoryId());
        assertEquals(ArticleService.STATUS_PENDING, article.getStatus());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(ArticlePublishMessage.class));
    }

    @Test
    void publishShouldRejectDirectCoverUrl() {
        Long userId = 10008L;
        String title = "封面文章-" + UUID.randomUUID();
        String coverUrl = "/uploads/article-cover/publish-cover.jpg";

        assertThrows(IllegalArgumentException.class,
                () -> articleService.publish(title, "带封面的待审核文章", defaultCategoryId(), userId, coverUrl));
        assertNull(findByTitle(title));
    }

    @Test
    void draftShouldNotAppearInPublicPage() {
        Long userId = 10002L;
        Article draft = articleService.saveDraft(
                "草稿文章-" + UUID.randomUUID(),
                "草稿不会出现在公开文章列表",
                defaultCategoryId(),
                userId);

        Page<Article> publicPage = articleService.pageArticles(1, 100, null);

        assertFalse(publicPage.getRecords().stream().anyMatch(article -> article.getId().equals(draft.getId())));
    }

    @Test
    void submitDraftShouldChangeStatusToPending() {
        Long userId = 10003L;
        Article draft = articleService.saveDraft(
                "提交审核文章-" + UUID.randomUUID(),
                "草稿提交后应进入待审核",
                defaultCategoryId(),
                userId);

        articleService.submit(userId, draft.getId());

        Article article = articleMapper.selectById(draft.getId());
        assertEquals(ArticleService.STATUS_PENDING, article.getStatus());
    }

    @Test
    void saveAndUpdateDraftShouldRejectDirectCoverUrl() {
        Long userId = 10009L;
        Long categoryId = defaultCategoryId();
        assertThrows(IllegalArgumentException.class, () -> articleService.saveDraft(
                "封面草稿-" + UUID.randomUUID(),
                "草稿封面初始值",
                categoryId,
                userId,
                "/uploads/article-cover/draft-old.webp"));

        Article draft = articleService.saveDraft("新草稿-" + UUID.randomUUID(), "正文", categoryId, userId);

        assertThrows(IllegalArgumentException.class, () -> articleService.updateDraft(
                userId,
                draft.getId(),
                "封面草稿更新",
                "草稿封面更新值",
                categoryId,
                "/uploads/article-cover/draft-new.png"));

        Article updated = articleMapper.selectById(draft.getId());
        assertNull(updated.getCoverUrl());
        assertEquals(ArticleService.STATUS_DRAFT, updated.getStatus());
    }

    @Test
    void approvePendingArticleShouldPublishAndSendMessage() {
        Long userId = 10004L;
        Long adminId = 90001L;
        String title = "审核通过文章-" + UUID.randomUUID();
        articleService.publish(title, "审核通过后才真正发布", defaultCategoryId(), userId);
        Article article = findByTitle(title);

        articleService.approve(article.getId(), adminId);

        Article published = articleMapper.selectById(article.getId());
        assertEquals(ArticleService.STATUS_PUBLISHED, published.getStatus());

        ArgumentCaptor<ArticlePublishMessage> messageCaptor = ArgumentCaptor.forClass(ArticlePublishMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.ARTICLE_EXCHANGE),
                eq(RabbitMQConfig.ARTICLE_PUBLISH_ROUTING_KEY),
                messageCaptor.capture());
        ArticlePublishMessage message = messageCaptor.getValue();
        assertEquals(article.getId(), message.getArticleId());
        assertEquals(userId, message.getUserId());
        assertEquals(adminId, message.getAuditorId());
        assertEquals(title, message.getTitle());
        assertNotNull(message.getMessageId());
        assertNotNull(message.getPublishTime());
    }

    @Test
    void rejectPendingArticleShouldRecordReason() {
        Long userId = 10005L;
        Long adminId = 90002L;
        String reason = "内容不符合板块要求";
        articleService.publish("审核驳回文章-" + UUID.randomUUID(), "需要记录驳回原因", defaultCategoryId(), userId);
        Article article = articleMapper.selectOne(
                new QueryWrapper<Article>().eq("user_id", userId).orderByDesc("id").last("LIMIT 1"));

        articleService.reject(article.getId(), adminId, reason);

        Article rejected = articleMapper.selectById(article.getId());
        assertEquals(ArticleService.STATUS_REJECTED, rejected.getStatus());

        ArticleAuditRecord record = articleAuditRecordMapper.selectOne(
                new QueryWrapper<ArticleAuditRecord>().eq("article_id", article.getId()));
        assertNotNull(record);
        assertEquals(adminId, record.getAuditorId());
        assertEquals(ArticleService.STATUS_REJECTED, record.getAuditStatus());
        assertEquals(reason, record.getReason());
    }

    @Test
    void offlineArticleShouldNotAppearInPublicPage() {
        Long userId = 10006L;
        Long adminId = 90003L;
        Article article = new Article();
        article.setTitle("下架文章-" + UUID.randomUUID());
        article.setContent("下架后不应公开展示");
        article.setUserId(userId);
        article.setCategoryId(defaultCategoryId());
        article.setStatus(ArticleService.STATUS_PUBLISHED);
        articleMapper.insert(article);

        articleService.offline(article.getId(), adminId, "违规内容");

        Article offline = articleMapper.selectById(article.getId());
        assertEquals(ArticleService.STATUS_OFFLINE, offline.getStatus());
        assertNull(articleService.findPublishedById(article.getId()));
        assertFalse(articleService.pageArticles(1, 100, null).getRecords().stream()
                .anyMatch(record -> record.getId().equals(article.getId())));
        verify(redisService).deleteArticleData(article.getId());
    }

    @Test
    void articleStatusShouldDefaultToPublishedWhenOmitted() {
        Article article = new Article();
        article.setTitle("默认状态文章-" + UUID.randomUUID());
        article.setContent("未显式设置 status 时应使用数据库默认值");
        article.setUserId(10007L);
        article.setCategoryId(defaultCategoryId());

        articleMapper.insert(article);

        Article saved = articleMapper.selectById(article.getId());
        assertEquals(ArticleService.STATUS_PUBLISHED, saved.getStatus());
    }

    @Test
    void userShouldFavoritePublishedArticle() {
        Long userId = 11001L;
        Article article = createArticle("收藏已发布文章-" + UUID.randomUUID(), "已发布文章可以被收藏", userId,
                defaultCategoryId(), ArticleService.STATUS_PUBLISHED);

        articleService.favoriteArticle(22001L, article.getId());

        Long favoriteCount = articleFavoriteMapper.selectCount(new QueryWrapper<ArticleFavorite>()
                .eq("user_id", 22001L)
                .eq("article_id", article.getId()));
        assertEquals(1, favoriteCount);
        assertEquals(1L, articleService.findPublishedById(article.getId()).getFavoriteCount());
    }

    @Test
    void favoriteOtherUsersArticleShouldCreateNotification() {
        Article article = createArticle("M4-fav-" + UUID.randomUUID(), "收藏他人文章后应通知作者", 31001L,
                defaultCategoryId(), ArticleService.STATUS_PUBLISHED);

        articleService.favoriteArticle(32001L, article.getId());

        Notification notification = findNotification(article.getId(), NotificationService.TYPE_FAVORITE);
        assertNotNull(notification);
        assertEquals(31001L, notification.getReceiverId());
        assertEquals(32001L, notification.getSenderId());
        assertEquals(NotificationService.READ_STATUS_UNREAD, notification.getReadStatus());
    }

    @Test
    void favoriteOwnArticleShouldNotCreateNotification() {
        Article article = createArticle("M4-own-fav-" + UUID.randomUUID(), "收藏自己的文章不生成通知", 31002L,
                defaultCategoryId(), ArticleService.STATUS_PUBLISHED);

        articleService.favoriteArticle(31002L, article.getId());

        assertEquals(0L, countNotifications(article.getId(), NotificationService.TYPE_FAVORITE));
    }

    @Test
    void userShouldNotFavoriteUnpublishedArticle() {
        Long categoryId = defaultCategoryId();
        String[] statuses = {
                ArticleService.STATUS_DRAFT,
                ArticleService.STATUS_PENDING,
                ArticleService.STATUS_REJECTED,
                ArticleService.STATUS_OFFLINE
        };

        for (int index = 0; index < statuses.length; index++) {
            String status = statuses[index];
            Article article = createArticle("不可收藏-" + index + "-" + UUID.randomUUID(), "只有已发布文章可以收藏",
                    11002L, categoryId, status);

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                articleService.favoriteArticle(22002L, article.getId());
            });

            assertEquals("只能收藏已发布文章", exception.getMessage());
        }
    }

    @Test
    void userShouldNotFavoriteSameArticleTwice() {
        Article article = createArticle("重复收藏文章-" + UUID.randomUUID(), "同一用户不能重复收藏同一篇文章", 11003L,
                defaultCategoryId(), ArticleService.STATUS_PUBLISHED);

        articleService.favoriteArticle(22003L, article.getId());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            articleService.favoriteArticle(22003L, article.getId());
        });

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("不能重复收藏同一篇文章", exception.getReason());
        assertEquals(1L, countNotifications(article.getId(), NotificationService.TYPE_FAVORITE));
    }

    @Test
    void concurrentDuplicateFavoriteShouldReturnConflictWithoutNotification() {
        Article article = createArticle("并发重复收藏-" + UUID.randomUUID(), "唯一约束冲突应统一返回 409", 11006L,
                defaultCategoryId(), ArticleService.STATUS_PUBLISHED);
        doThrow(new DuplicateKeyException("duplicate favorite"))
                .when(articleFavoriteMapper).insert(any(ArticleFavorite.class));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> articleService.favoriteArticle(22006L, article.getId()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("不能重复收藏同一篇文章", exception.getReason());
        assertEquals(0L, countNotifications(article.getId(), NotificationService.TYPE_FAVORITE));
    }

    @Test
    void approveNotificationShouldBeHandledByMqConsumer() {
        Long authorId = 33001L;
        Long adminId = 93001L;
        String title = "M4-approve-" + UUID.randomUUID();
        articleService.publish(title, "审核通过后应通知作者", defaultCategoryId(), authorId);
        Article article = findByTitle(title);

        articleService.approve(article.getId(), adminId);

        assertNull(findNotification(article.getId(), NotificationService.TYPE_AUDIT_APPROVED));
    }

    @Test
    void rejectShouldCreateAuditRejectedNotificationWithReason() {
        Long authorId = 33002L;
        Long adminId = 93002L;
        String reason = "M4 驳回原因";
        String title = "M4-reject-" + UUID.randomUUID();
        articleService.publish(title, "审核驳回后应通知作者", defaultCategoryId(), authorId);
        Article article = findByTitle(title);

        articleService.reject(article.getId(), adminId, reason);

        Notification notification = findNotification(article.getId(), NotificationService.TYPE_AUDIT_REJECTED);
        assertNotNull(notification);
        assertEquals(authorId, notification.getReceiverId());
        assertEquals(adminId, notification.getSenderId());
        assertTrue(notification.getContent().contains(reason));
    }

    @Test
    void offlineShouldCreateArticleOfflineNotificationWithReason() {
        Long authorId = 33003L;
        Long adminId = 93003L;
        String reason = "M4 下架原因";
        Article article = createArticle("M4-offline-" + UUID.randomUUID(), "下架后应通知作者", authorId,
                defaultCategoryId(), ArticleService.STATUS_PUBLISHED);

        articleService.offline(article.getId(), adminId, reason);

        Notification notification = findNotification(article.getId(), NotificationService.TYPE_ARTICLE_OFFLINE);
        assertNotNull(notification);
        assertEquals(authorId, notification.getReceiverId());
        assertEquals(adminId, notification.getSenderId());
        assertTrue(notification.getContent().contains(reason));
    }

    @Test
    void userShouldUnfavoriteOwnFavoriteAndFavoriteAgain() {
        Article article = createArticle("取消收藏文章-" + UUID.randomUUID(), "用户可以取消自己的收藏", 11004L,
                defaultCategoryId(), ArticleService.STATUS_PUBLISHED);

        articleService.favoriteArticle(22004L, article.getId());
        articleService.unfavoriteArticle(22004L, article.getId());

        Long favoriteCount = articleFavoriteMapper.selectCount(new QueryWrapper<ArticleFavorite>()
                .eq("user_id", 22004L)
                .eq("article_id", article.getId()));
        assertEquals(0, favoriteCount);

        articleService.favoriteArticle(22004L, article.getId());
        assertEquals(1L, articleService.findPublishedById(article.getId()).getFavoriteCount());
    }

    @Test
    void pageFavoriteArticlesShouldOnlyReturnCurrentUsersPublishedFavorites() {
        Long categoryId = defaultCategoryId();
        Article published = createArticle("我的收藏已发布文章-" + UUID.randomUUID(), "应该出现在我的收藏", 11005L,
                categoryId, ArticleService.STATUS_PUBLISHED);
        Article offline = createArticle("我的收藏下架文章-" + UUID.randomUUID(), "下架后不应出现在我的收藏", 11005L,
                categoryId, ArticleService.STATUS_OFFLINE);
        Article otherUsersFavorite = createArticle("其他用户收藏文章-" + UUID.randomUUID(), "不属于当前用户收藏", 11005L,
                categoryId, ArticleService.STATUS_PUBLISHED);

        articleService.favoriteArticle(22005L, published.getId());
        insertFavorite(22005L, offline.getId());
        articleService.favoriteArticle(22006L, otherUsersFavorite.getId());

        Page<Article> favoritePage = articleService.pageFavoriteArticles(22005L, 1, 10);

        assertTrue(favoritePage.getRecords().stream().anyMatch(article -> article.getId().equals(published.getId())));
        assertFalse(favoritePage.getRecords().stream().anyMatch(article -> article.getId().equals(offline.getId())));
        assertFalse(favoritePage.getRecords().stream()
                .anyMatch(article -> article.getId().equals(otherUsersFavorite.getId())));
    }

    @Test
    void searchShouldOnlyReturnPublishedArticlesByKeywordAndCategory() {
        Category matchedCategory = categoryService.create("搜索板块-" + UUID.randomUUID(), "搜索测试板块", 1);
        Category otherCategory = categoryService.create("其他搜索板块-" + UUID.randomUUID(), "搜索测试板块", 2);
        Article titleMatched = createArticle("M3搜索标题-" + UUID.randomUUID(), "普通内容", 11006L,
                matchedCategory.getId(), ArticleService.STATUS_PUBLISHED);
        Article contentMatched = createArticle("普通标题-" + UUID.randomUUID(), "正文包含 M3搜索关键词", 11006L,
                matchedCategory.getId(), ArticleService.STATUS_PUBLISHED);
        Article draftMatched = createArticle("M3搜索草稿-" + UUID.randomUUID(), "草稿不公开", 11006L,
                matchedCategory.getId(), ArticleService.STATUS_DRAFT);
        Article otherCategoryMatched = createArticle("M3搜索其他板块-" + UUID.randomUUID(), "其他板块文章", 11006L,
                otherCategory.getId(), ArticleService.STATUS_PUBLISHED);

        Page<Article> allMatched = articleService.searchPublishedArticles("M3搜索", null, 1, 10);

        assertTrue(allMatched.getRecords().stream().anyMatch(article -> article.getId().equals(titleMatched.getId())));
        assertTrue(allMatched.getRecords().stream().anyMatch(article -> article.getId().equals(contentMatched.getId())));
        assertFalse(allMatched.getRecords().stream().anyMatch(article -> article.getId().equals(draftMatched.getId())));

        Page<Article> categoryMatched = articleService.searchPublishedArticles("M3搜索", matchedCategory.getId(), 1, 10);

        assertTrue(categoryMatched.getRecords().stream()
                .allMatch(article -> article.getCategoryId().equals(matchedCategory.getId())));
        assertFalse(categoryMatched.getRecords().stream()
                .anyMatch(article -> article.getId().equals(otherCategoryMatched.getId())));
    }

    @Test
    void pagePublishedArticlesByUserShouldOnlyReturnPublishedArticles() {
        Long authorId = 12010L;
        Long categoryId = defaultCategoryId();
        Article published = createArticle("公开主页文章-" + UUID.randomUUID(), "应出现在公开主页", authorId,
                categoryId, ArticleService.STATUS_PUBLISHED);
        Article draft = createArticle("公开主页草稿-" + UUID.randomUUID(), "草稿不公开", authorId,
                categoryId, ArticleService.STATUS_DRAFT);
        Article offline = createArticle("公开主页下架-" + UUID.randomUUID(), "下架不公开", authorId,
                categoryId, ArticleService.STATUS_OFFLINE);
        Article otherAuthor = createArticle("其他作者文章-" + UUID.randomUUID(), "不属于当前作者", 12011L,
                categoryId, ArticleService.STATUS_PUBLISHED);

        Page<Article> result = articleService.pagePublishedArticlesByUser(authorId, 1, 10);

        assertTrue(result.getRecords().stream().anyMatch(article -> article.getId().equals(published.getId())));
        assertFalse(result.getRecords().stream().anyMatch(article -> article.getId().equals(draft.getId())));
        assertFalse(result.getRecords().stream().anyMatch(article -> article.getId().equals(offline.getId())));
        assertFalse(result.getRecords().stream().anyMatch(article -> article.getId().equals(otherAuthor.getId())));
        assertTrue(result.getRecords().stream()
                .allMatch(article -> ArticleService.STATUS_PUBLISHED.equals(article.getStatus())));
    }

    @Test
    void articlePageShouldFillCategoryAndFavoriteCountsInBatch() {
        Long categoryId = defaultCategoryId();
        Category category = categoryMapper.selectById(categoryId);
        Article first = createArticle("批量元数据-1-" + UUID.randomUUID(), "列表应批量补齐元数据", 12101L,
                categoryId, ArticleService.STATUS_PUBLISHED);
        Article second = createArticle("批量元数据-2-" + UUID.randomUUID(), "列表应批量补齐元数据", 12102L,
                categoryId, ArticleService.STATUS_PUBLISHED);
        insertFavorite(22101L, first.getId());
        insertFavorite(22102L, second.getId());
        insertFavorite(22103L, second.getId());

        Page<Article> result = articleService.pageArticles(1, 100);
        Article firstResult = result.getRecords().stream()
                .filter(article -> article.getId().equals(first.getId()))
                .findFirst()
                .orElseThrow();
        Article secondResult = result.getRecords().stream()
                .filter(article -> article.getId().equals(second.getId()))
                .findFirst()
                .orElseThrow();

        assertEquals(category.getName(), firstResult.getCategoryName());
        assertEquals(category.getName(), secondResult.getCategoryName());
        assertEquals(1L, firstResult.getFavoriteCount());
        assertEquals(2L, secondResult.getFavoriteCount());
    }

    @Test
    void authorShouldNotUpdatePublishedArticle() {
        Article article = createArticle("禁止修改已发布文章-" + UUID.randomUUID(), "已发布内容", 12001L,
                defaultCategoryId(), ArticleService.STATUS_PUBLISHED);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            articleService.update(12001L, article.getId(), "绕过审核的新标题", "绕过审核的新内容");
        });

        assertEquals("只能修改草稿或被驳回文章", exception.getMessage());
        Article saved = articleMapper.selectById(article.getId());
        assertEquals("已发布内容", saved.getContent());
    }

    @Test
    void authorShouldUpdateDraftAndRejectedArticle() {
        Article draft = createArticle("可修改草稿-" + UUID.randomUUID(), "旧草稿内容", 12002L,
                defaultCategoryId(), ArticleService.STATUS_DRAFT);
        Article rejected = createArticle("可修改驳回文章-" + UUID.randomUUID(), "旧驳回内容", 12002L,
                defaultCategoryId(), ArticleService.STATUS_REJECTED);

        articleService.update(12002L, draft.getId(), "新草稿标题", "新草稿内容");
        articleService.update(12002L, rejected.getId(), "新驳回标题", "新驳回内容");

        assertEquals("新草稿内容", articleMapper.selectById(draft.getId()).getContent());
        assertEquals("新驳回内容", articleMapper.selectById(rejected.getId()).getContent());
    }

    @Test
    void authorShouldRejectDirectCoverUrlForDraftArticle() {
        Article draft = createArticle("可修改封面草稿-" + UUID.randomUUID(), "旧草稿内容", 12005L,
                defaultCategoryId(), ArticleService.STATUS_DRAFT);

        assertThrows(IllegalArgumentException.class, () -> articleService.update(
                12005L,
                draft.getId(),
                "新草稿标题",
                "新草稿内容",
                "/uploads/article-cover/updated-cover.jpg"));

        Article updated = articleMapper.selectById(draft.getId());
        assertNull(updated.getCoverUrl());
    }

    @Test
    void nonAuthorShouldNotUpdateArticle() {
        Article article = createArticle("非作者禁止修改-" + UUID.randomUUID(), "原内容", 12003L,
                defaultCategoryId(), ArticleService.STATUS_DRAFT);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            articleService.update(12004L, article.getId(), "非法标题", "非法内容");
        });

        assertEquals("只能修改自己的文章", exception.getMessage());
        assertEquals("原内容", articleMapper.selectById(article.getId()).getContent());
    }

    @Test
    void nonAuthorShouldNotDeleteArticle() {
        Long authorId = 20001L;
        Long otherUserId = 20002L;

        Article article = new Article();
        article.setTitle("权限测试文章-" + UUID.randomUUID());
        article.setContent("这是一篇测试非作者不能删除的文章");
        article.setUserId(authorId);
        article.setCategoryId(defaultCategoryId());
        article.setStatus(ArticleService.STATUS_PUBLISHED);
        articleMapper.insert(article);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            articleService.delete(otherUserId, article.getId());
        });

        assertEquals("只能删除自己的文章", exception.getMessage());
        assertNotNull(articleMapper.selectById(article.getId()));
    }

    @Test
    void adminShouldDeleteArticleAndCleanRelatedData() {
        Article article = new Article();
        article.setTitle("管理员删除文章-" + UUID.randomUUID());
        article.setContent("管理员删除时应该同时清理评论和 Redis");
        article.setUserId(30001L);
        article.setCategoryId(defaultCategoryId());
        article.setStatus(ArticleService.STATUS_PUBLISHED);
        articleMapper.insert(article);

        Comment comment = new Comment();
        comment.setArticleId(article.getId());
        comment.setUserId(30002L);
        comment.setContent("这条评论应该跟随文章一起被删除");
        comment.setParentCommentId(0L);
        commentMapper.insert(comment);

        boolean deleted = articleService.deleteByAdmin(article.getId());

        assertTrue(deleted);
        assertNull(articleMapper.selectById(article.getId()));
        assertEquals(0, commentMapper.selectCount(
                new QueryWrapper<Comment>().eq("article_id", article.getId())));
        verify(redisService).deleteArticleData(article.getId());
    }

    @Test
    void disabledCategoryShouldNotPublishArticle() {
        Long userId = 20003L;
        Category category = categoryService.create("禁用发文板块-" + UUID.randomUUID(), "禁用发文测试", 1);
        categoryService.disable(category.getId());

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            articleService.publish("禁用板块文章", "这篇文章不应该发布成功", category.getId(), userId);
        });

        assertEquals("板块不存在或已禁用", exception.getMessage());
    }

    private Article findByTitle(String title) {
        QueryWrapper<Article> wrapper = new QueryWrapper<>();
        wrapper.eq("title", title);
        return articleMapper.selectOne(wrapper);
    }

    private Long defaultCategoryId() {
        Category category = categoryMapper.selectList(null).get(0);
        return category.getId();
    }

    private Article createArticle(String title, String content, Long userId, Long categoryId, String status) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent(content);
        article.setUserId(userId);
        article.setCategoryId(categoryId);
        article.setStatus(status);
        articleMapper.insert(article);
        return article;
    }

    private void insertFavorite(Long userId, Long articleId) {
        ArticleFavorite favorite = new ArticleFavorite();
        favorite.setUserId(userId);
        favorite.setArticleId(articleId);
        articleFavoriteMapper.insert(favorite);
    }

    private Notification findNotification(Long articleId, String type) {
        return notificationMapper.selectOne(new QueryWrapper<Notification>()
                .eq("article_id", articleId)
                .eq("type", type)
                .last("LIMIT 1"));
    }

    private Long countNotifications(Long articleId, String type) {
        return notificationMapper.selectCount(new QueryWrapper<Notification>()
                .eq("article_id", articleId)
                .eq("type", type));
    }
}
