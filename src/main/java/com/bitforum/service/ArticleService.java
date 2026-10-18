package com.bitforum.service;

import com.bitforum.exception.BusinessException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.ai.trace.TraceHeaders;
import com.bitforum.config.RabbitMQConfig;
import com.bitforum.entity.Article;
import com.bitforum.entity.ArticleAuditRecord;
import com.bitforum.entity.ArticleFavorite;
import com.bitforum.entity.Category;
import com.bitforum.entity.Comment;
import com.bitforum.mapper.ArticleAuditRecordMapper;
import com.bitforum.mapper.ArticleFavoriteMapper;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CategoryMapper;
import com.bitforum.mapper.CommentMapper;
import com.bitforum.message.AfterCommitExecutor;
import com.bitforum.message.ArticlePublishMessage;
import com.bitforum.message.KbIndexMessage;
import com.bitforum.message.ModerationMessage;

@Service
public class ArticleService {
    public static final String FORMAT_PLAIN_TEXT = "PLAIN_TEXT";
    public static final String FORMAT_MARKDOWN = "MARKDOWN";

    public static final String TYPE_ARTICLE = "ARTICLE";
    public static final String TYPE_QUESTION = "QUESTION";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_OFFLINE = "OFFLINE";

    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private ArticleAuditRecordMapper articleAuditRecordMapper;
    @Autowired
    private ArticleFavoriteMapper articleFavoriteMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AfterCommitExecutor afterCommitExecutor;
    @Autowired
    private CommentService commentService;
    @Autowired
    private RedisService redisService;
    @Autowired
    private CategoryService categoryService;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private TextReviewService textReviewService;
    @Autowired
    private ImageResourceService imageResourceService;
    @Autowired
    private UserService userService;
    @Autowired
    private FollowFeedCursor followFeedCursor;

    private static final Logger log = LoggerFactory.getLogger(ArticleService.class);

    @Transactional
    public void publish(String title, String content, Long categoryId, Long userId) {
        publish(title, content, categoryId, userId, null);
    }

    @Transactional
    public void publish(String title, String content, Long categoryId, Long userId, String coverUrl) {
        rejectCoverUrl(coverUrl);
        publishWithCoverId(title, content, categoryId, userId, null);
    }

    @Transactional
    public void publishWithCoverId(String title, String content, Long categoryId, Long userId, Long coverImageId) {
        publishWithCoverId(title, content, categoryId, userId, coverImageId, null);
    }

    @Transactional
    public void publishWithCoverId(String title, String content, Long categoryId, Long userId, Long coverImageId, String contentType) {
        publishWithCoverId(title, content, categoryId, userId, coverImageId, contentType, null);
    }

    @Transactional
    public void publishWithCoverId(String title, String content, Long categoryId, Long userId, Long coverImageId, String contentType, String contentFormat) {
        createArticle(title, content, categoryId, userId, STATUS_PENDING, coverImageId, contentType, contentFormat);
        log.info("文章已提交审核");
    }

    @Transactional
    public Article saveDraft(String title, String content, Long categoryId, Long userId) {
        return saveDraft(title, content, categoryId, userId, null);
    }

    @Transactional
    public Article saveDraft(String title, String content, Long categoryId, Long userId, String coverUrl) {
        rejectCoverUrl(coverUrl);
        return saveDraftWithCoverId(title, content, categoryId, userId, null);
    }

    @Transactional
    public Article saveDraftWithCoverId(String title, String content, Long categoryId, Long userId, Long coverImageId) {
        return saveDraftWithCoverId(title, content, categoryId, userId, coverImageId, null);
    }

    @Transactional
    public Article saveDraftWithCoverId(String title, String content, Long categoryId, Long userId, Long coverImageId, String contentType) {
        return saveDraftWithCoverId(title, content, categoryId, userId, coverImageId, contentType, null);
    }

    @Transactional
    public Article saveDraftWithCoverId(String title, String content, Long categoryId, Long userId, Long coverImageId, String contentType, String contentFormat) {
        return createArticle(title, content, categoryId, userId, STATUS_DRAFT, coverImageId, contentType, contentFormat);
    }

    @Transactional
    public void updateDraft(Long userId, Long articleId, String title, String content, Long categoryId) {
        updateDraft(userId, articleId, title, content, categoryId, null);
    }

    @Transactional
    public void updateDraft(Long userId, Long articleId, String title, String content, Long categoryId, String coverUrl) {
        rejectCoverUrl(coverUrl);
        updateDraftWithCoverId(userId, articleId, title, content, categoryId, null);
    }

    @Transactional
    public void updateDraftWithCoverId(Long userId, Long articleId, String title, String content, Long categoryId, Long coverImageId) {
        updateDraftWithCoverId(userId, articleId, title, content, categoryId, coverImageId, null);
    }

    @Transactional
    public void updateDraftWithCoverId(Long userId, Long articleId, String title, String content, Long categoryId, Long coverImageId, String contentFormat) {
        Article article = findOwnedArticle(userId, articleId);
        if (!STATUS_DRAFT.equals(article.getStatus()) && !STATUS_REJECTED.equals(article.getStatus())) {
            throw new BusinessException("只能修改草稿或被驳回文章");
        }
        Category category = categoryService.findEnabledById(categoryId);
        if (category == null) {
            throw new BusinessException("板块不存在或已禁用");
        }

        article.setContentFormat(validateContentFormat(contentFormat == null ? article.getContentFormat() : contentFormat));
        article.setTitle(title);
        article.setContent(content);
        bindCover(article, userId, coverImageId);
        article.setCategoryId(categoryId);
        article.setStatus(STATUS_DRAFT);
        articleMapper.updateById(article);
    }

    @Transactional
    public void submit(Long userId, Long articleId) {
        Article article = findOwnedArticle(userId, articleId);
        if (!STATUS_DRAFT.equals(article.getStatus()) && !STATUS_REJECTED.equals(article.getStatus())) {
            throw new BusinessException("只能提交草稿或被驳回文章");
        }
        Category category = categoryService.findEnabledById(article.getCategoryId());
        if (category == null) {
            throw new BusinessException("板块不存在或已禁用");
        }
        article.setStatus(STATUS_PENDING);
        articleMapper.updateById(article);
        textReviewService.recordIfMatched(TextReviewService.ARTICLE, articleId,
                article.getTitle() + "\n" + article.getContent(), "NEW", null, null);
        // M16：进入待审核状态后异步送 AI 分析。AI 只提供建议，是否通过仍由人工决定
        //（自动放行需显式开启开关，且仅限高置信 PASS）。
        sendModerationMessage(articleId);
    }

    public List<Article> listAll() {
        QueryWrapper<Article> wrapper = new QueryWrapper<>();
        wrapper.eq("status", STATUS_PUBLISHED);
        wrapper.orderByDesc("create_time", "id");
        List<Article> articleList = articleMapper.selectList(wrapper);
        fillArticleMetadata(articleList);
        fillCommunityPresentation(articleList);
        return articleList;
    }

    public Page<Article> pageArticles(long pageNum, long pageSize) {
        return pageArticles(pageNum, pageSize, null);
    }

    public Page<Article> pageArticles(long pageNum, long pageSize, Long categoryId) {
        return pageArticles(pageNum, pageSize, categoryId, null);
    }

    public Page<Article> pageArticles(long pageNum, long pageSize, Long categoryId, String contentType) {
        Page<Article> page = new Page<>(pageNum, pageSize);
        QueryWrapper<Article> wrapper = new QueryWrapper<>();
        wrapper.eq("status", STATUS_PUBLISHED);
        if (contentType != null) wrapper.eq("content_type", validateContentType(contentType));
        if (categoryId != null) {
            wrapper.eq("category_id", categoryId);
        }
        wrapper.orderByDesc("create_time", "id");
        Page<Article> result = articleMapper.selectPage(page, wrapper);
        fillArticleMetadata(result.getRecords());
        fillCommunityPresentation(result.getRecords());
        return result;
    }

    public Page<Article> searchPublishedArticles(String keyword, Long categoryId, long pageNum, long pageSize) {
        return searchPublishedArticles(keyword, categoryId, pageNum, pageSize, null);
    }

    public Page<Article> searchPublishedArticles(String keyword, Long categoryId, long pageNum, long pageSize, String contentType) {
        Page<Article> page = new Page<>(pageNum, pageSize);
        QueryWrapper<Article> wrapper = new QueryWrapper<>();
        wrapper.eq("status", STATUS_PUBLISHED);
        if (contentType != null) wrapper.eq("content_type", validateContentType(contentType));
        if (StringUtils.hasText(keyword)) {
            wrapper.and(query -> query.like("title", keyword).or().like("content", keyword));
        }
        if (categoryId != null) {
            wrapper.eq("category_id", categoryId);
        }
        wrapper.orderByDesc("create_time", "id");
        Page<Article> result = articleMapper.selectPage(page, wrapper);
        fillArticleMetadata(result.getRecords());
        fillCommunityPresentation(result.getRecords());
        return result;
    }

    public com.bitforum.dto.FollowFeedPage followFeed(Long userId, long pageSize, String contentType, String cursor) {
        TopicFollowService.validPage(1, pageSize);
        TopicFollowService.validType(contentType);
        FollowFeedCursor.Boundary boundary = followFeedCursor.decode(cursor, userId, contentType);
        List<Article> records = articleMapper.selectFollowFeed(userId, contentType,
                boundary == null ? null : boundary.time(), boundary == null ? null : boundary.id(), pageSize + 1);
        boolean more = records.size() > pageSize;
        if (more) records = new java.util.ArrayList<>(records.subList(0, (int) pageSize));
        fillArticleMetadata(records);
        fillCommunityPresentation(records);
        String next = null;
        if (more) {
            Article last = records.get(records.size() - 1);
            next = followFeedCursor.encode(userId, contentType, last.getCreateTime(), last.getId());
        }
        return new com.bitforum.dto.FollowFeedPage(records, next, more);
    }

    public Page<Article> pageUserArticles(Long userId, long pageNum, long pageSize, String status) {
        Page<Article> page = new Page<>(pageNum, pageSize);
        QueryWrapper<Article> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        if (StringUtils.hasText(status)) {
            validateStatus(status);
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("create_time");
        Page<Article> result = articleMapper.selectPage(page, wrapper);
        fillArticleMetadata(result.getRecords());
        fillCommunityPresentation(result.getRecords());
        return result;
    }

    public Page<Article> pagePublishedArticlesByUser(Long userId, long pageNum, long pageSize) {
        Page<Article> page = new Page<>(pageNum, pageSize);
        QueryWrapper<Article> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        wrapper.eq("status", STATUS_PUBLISHED);
        wrapper.orderByDesc("create_time");
        Page<Article> result = articleMapper.selectPage(page, wrapper);
        fillArticleMetadata(result.getRecords());
        fillCommunityPresentation(result.getRecords());
        return result;
    }

    public Page<Article> pageAdminArticles(long pageNum, long pageSize, Long categoryId) {
        Page<Article> page = new Page<>(pageNum, pageSize);
        QueryWrapper<Article> wrapper = new QueryWrapper<>();
        if (categoryId != null) {
            wrapper.eq("category_id", categoryId);
        }
        wrapper.orderByDesc("create_time");
        Page<Article> result = articleMapper.selectPage(page, wrapper);
        fillArticleMetadata(result.getRecords());
        return result;
    }

    public Page<Article> pageAuditArticles(long pageNum, long pageSize, String status) {
        String auditStatus = StringUtils.hasText(status) ? status : STATUS_PENDING;
        validateStatus(auditStatus);

        Page<Article> page = new Page<>(pageNum, pageSize);
        QueryWrapper<Article> wrapper = new QueryWrapper<>();
        wrapper.eq("status", auditStatus);
        wrapper.orderByDesc("create_time");
        Page<Article> result = articleMapper.selectPage(page, wrapper);
        fillArticleMetadata(result.getRecords());
        return result;
    }

    public Article findById(Long id) {
        Article article = articleMapper.selectById(id);
        fillArticleMetadata(article);
        return article;
    }

    public Article findPublishedById(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null || !STATUS_PUBLISHED.equals(article.getStatus())) {
            return null;
        }
        fillArticleMetadata(article);
        return article;
    }

    /**
     * 按可见性查询文章详情。
     *
     * 已发布文章对所有人可见；草稿、待审核、已驳回、已下架仅作者本人可见。
     * 这样作者能预览自己的未公开内容，同时不会通过详情接口泄露他人的草稿。
     *
     * @param id            文章 id
     * @param currentUserId 当前登录用户 id；未登录传 null
     * @return 可见时返回文章，不可见或不存在时返回 null
     */
    public Article findReadableById(Long id, Long currentUserId) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            return null;
        }
        boolean published = STATUS_PUBLISHED.equals(article.getStatus());
        boolean owner = currentUserId != null && currentUserId.equals(article.getUserId());
        if (!published && !owner) {
            return null;
        }
        fillArticleMetadata(article);
        fillCommunityPresentation(List.of(article));
        return article;
    }

    @Transactional
    public void favoriteArticle(Long userId, Long articleId) {
        Article article = articleMapper.selectById(articleId);
        if (article == null || !STATUS_PUBLISHED.equals(article.getStatus())) {
            throw new BusinessException("只能收藏已发布文章");
        }
        Long count = articleFavoriteMapper.selectCount(new QueryWrapper<ArticleFavorite>()
                .eq("user_id", userId)
                .eq("article_id", articleId));
        if (count > 0) {
            throw favoriteConflict();
        }

        ArticleFavorite favorite = new ArticleFavorite();
        favorite.setUserId(userId);
        favorite.setArticleId(articleId);
        try {
            articleFavoriteMapper.insert(favorite);
        } catch (DuplicateKeyException e) {
            throw favoriteConflict();
        }
        notificationService.notifyFavorite(article, userId);
    }

    @Transactional
    public void unfavoriteArticle(Long userId, Long articleId) {
        int deleted = articleFavoriteMapper.delete(new QueryWrapper<ArticleFavorite>()
                .eq("user_id", userId)
                .eq("article_id", articleId));
        if (deleted == 0) {
            throw new BusinessException("收藏记录不存在或不属于当前用户");
        }
    }

    /**
     * 查询某用户是否收藏了这篇文章。
     *
     * <p>详情接口据此回填 {@code favoritedByCurrentUser}，让收藏按钮能表达真实状态。
     * 未登录时 userId 为 null，直接返回 false —— 匿名访客的状态是"确定未收藏"，而不是"不知道"。
     */
    public boolean isFavoritedBy(Long userId, Long articleId) {
        if (userId == null || articleId == null) {
            return false;
        }
        Long count = articleFavoriteMapper.selectCount(new QueryWrapper<ArticleFavorite>()
                .eq("user_id", userId)
                .eq("article_id", articleId));
        return count != null && count > 0;
    }

    public Page<Article> pageFavoriteArticles(Long userId, long pageNum, long pageSize) {
        Page<Article> page = new Page<>(pageNum, pageSize);
        Page<Article> result = articleMapper.selectFavoriteArticles(page, userId, STATUS_PUBLISHED);
        fillArticleMetadata(result.getRecords());
        fillCommunityPresentation(result.getRecords());
        return result;
    }

    @Transactional
    public void update(Long userId, Long articleId, String title, String content) {
        update(userId, articleId, title, content, null);
    }

    @Transactional
    public void update(Long userId, Long articleId, String title, String content, String coverUrl) {
        rejectCoverUrl(coverUrl);
        updateWithCoverId(userId, articleId, title, content, null);
    }

    @Transactional
    public void updateWithCoverId(Long userId, Long articleId, String title, String content, Long coverImageId) {
        updateWithCoverId(userId, articleId, title, content, coverImageId, null);
    }

    @Transactional
    public void updateWithCoverId(Long userId, Long articleId, String title, String content, Long coverImageId, String contentFormat) {
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BusinessException("文章不存在");
        }
        if (!article.getUserId().equals(userId)) {
            throw new BusinessException("只能修改自己的文章");
        }
        if (!STATUS_DRAFT.equals(article.getStatus()) && !STATUS_REJECTED.equals(article.getStatus())) {
            throw new BusinessException("只能修改草稿或被驳回文章");
        }
        article.setContentFormat(validateContentFormat(contentFormat == null ? article.getContentFormat() : contentFormat));
        article.setTitle(title);
        article.setContent(content);
        bindCover(article, userId, coverImageId);
        articleMapper.updateById(article);
    }

    @Transactional
    public void delete(Long userId, Long articleId) {
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BusinessException("文章不存在");
        }
        if (!article.getUserId().equals(userId)) {
            throw new BusinessException("只能删除自己的文章");
        }
        deleteArticleWithRelatedData(articleId);
    }

    @Transactional
    public boolean deleteByAdmin(Long articleId) {
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            return false;
        }
        deleteArticleWithRelatedData(articleId);
        return true;
    }

    @Transactional
    public boolean approve(Long articleId, Long auditorId) {
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BusinessException("文章不存在");
        }
        if (!STATUS_PENDING.equals(article.getStatus())) {
            throw new BusinessException("只能审核待审核文章");
        }
        if (!imageResourceService.isApproved(article.getCoverImageId())) {
            throw new BusinessException("文章封面尚未通过人工审核");
        }
        boolean ruleHit = textReviewService.recordIfMatched(TextReviewService.ARTICLE, articleId,
                article.getTitle() + "\n" + article.getContent(), "NEW", null, null);
        if (ruleHit && Long.valueOf(0L).equals(auditorId)) {
            // 保留刚创建的待办；抛异常会使当前事务连同待办一起回滚。
            return false;
        }
        article.setStatus(STATUS_PUBLISHED);
        articleMapper.updateById(article);
        recordAudit(articleId, auditorId, STATUS_PUBLISHED, ruleHit ? "管理员确认文字规则命中" : null);
        textReviewService.finishArticle(articleId, auditorId, true, ruleHit ? "管理员确认文字规则命中" : null);
        sendPublishMessage(article, auditorId);
        // M15：文章进入已发布状态，异步写入知识库
        sendKbIndexMessage(articleId);
        log.info("文章审核通过，MQ 消息已发送");
        return true;
    }

    @Transactional
    public void reject(Long articleId, Long auditorId, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException("驳回原因不能为空");
        }
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BusinessException("文章不存在");
        }
        if (!STATUS_PENDING.equals(article.getStatus())) {
            throw new BusinessException("只能审核待审核文章");
        }
        article.setStatus(STATUS_REJECTED);
        articleMapper.updateById(article);
        recordAudit(articleId, auditorId, STATUS_REJECTED, reason);
        textReviewService.finishArticle(articleId, auditorId, false, reason);
        notificationService.notifyAuditRejected(article, auditorId, reason);
    }

    @Transactional
    public void offline(Long articleId, Long auditorId, String reason) {
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BusinessException("文章不存在");
        }
        if (!STATUS_PUBLISHED.equals(article.getStatus())) {
            throw new BusinessException("只能下架已发布文章");
        }
        article.setStatus(STATUS_OFFLINE);
        articleMapper.updateById(article);
        recordAudit(articleId, auditorId, STATUS_OFFLINE, reason);
        redisService.deleteArticleData(articleId);
        notificationService.notifyArticleOffline(article, auditorId, reason);
        // M15：文章离开已发布状态，异步把它的向量移出知识库
        sendKbIndexMessage(articleId);
    }

    @Transactional
    public void holdForTextReview(Long articleId, Long auditorId) {
        Article article = articleMapper.selectById(articleId);
        if (article == null || !STATUS_PUBLISHED.equals(article.getStatus())) {
            return;
        }
        article.setStatus(STATUS_PENDING);
        articleMapper.updateById(article);
        recordAudit(articleId, auditorId, STATUS_PENDING, "文字规则复扫：待人工审核");
        try {
            redisService.deleteArticleData(articleId);
        } catch (RuntimeException e) {
            // MySQL 状态是公开可见性的依据；Redis 故障不能让历史命中内容继续公开。
            log.warn("文字复扫文章转待审后清理 Redis 失败：articleId={}", articleId, e);
        }
        sendKbIndexMessage(articleId);
    }

    private Article createArticle(String title, String content, Long categoryId, Long userId, String status, Long coverImageId, String contentType, String contentFormat) {
        Category category = categoryService.findEnabledById(categoryId);
        if (category == null) {
            throw new BusinessException("板块不存在或已禁用");
        }

        Article article = new Article();
        article.setContentFormat(validateContentFormat(contentFormat));
        article.setTitle(title);
        article.setContent(content);
        article.setCategoryId(categoryId);
        article.setUserId(userId);
        article.setStatus(status);
        article.setContentType(validateContentType(contentType));
        articleMapper.insert(article);
        bindCover(article, userId, coverImageId);
        if (STATUS_PENDING.equals(status)) {
            textReviewService.recordIfMatched(TextReviewService.ARTICLE, article.getId(),
                    title + "\n" + content, "NEW", null, null);
            sendModerationMessage(article.getId());
        }
        fillArticleMetadata(article);
        return article;
    }

    private void bindCover(Article article, Long userId, Long coverImageId) {
        var image = imageResourceService.bindCover(userId, article.getId(), coverImageId);
        article.setCoverImageId(coverImageId);
        article.setCoverUrl(imageResourceService.publicUrl(image));
        articleMapper.updateCoverBinding(article.getId(), coverImageId, article.getCoverUrl());
    }

    private String validateContentFormat(String format) {
        if (format == null) return FORMAT_PLAIN_TEXT;
        if (!FORMAT_PLAIN_TEXT.equals(format) && !FORMAT_MARKDOWN.equals(format)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "正文格式只能为 PLAIN_TEXT 或 MARKDOWN");
        }
        return format;
    }

    private String validateContentType(String type) {
        if (type == null) return TYPE_ARTICLE;
        if (!TYPE_ARTICLE.equals(type) && !TYPE_QUESTION.equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容类型只能为 ARTICLE 或 QUESTION");
        }
        return type;
    }

    @Transactional
    public void acceptAnswer(Long userId, Long articleId, Long answerId) {
        Article question = articleMapper.lockById(articleId);
        if (question == null || !TYPE_QUESTION.equals(question.getContentType())
                || !STATUS_PUBLISHED.equals(question.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "公开问题不存在");
        }
        if (!userId.equals(question.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有问题作者可以采纳回答");
        }
        if (answerId != null) {
            var answer = commentMapper.lockById(answerId);
            if (answer == null || !articleId.equals(answer.getArticleId())
                    || !CommentService.STATUS_PUBLISHED.equals(answer.getStatus())
                    || !Long.valueOf(0).equals(answer.getParentCommentId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能采纳本问题已公开的顶层回答");
            }
        }
        articleMapper.setAcceptedAnswer(articleId, answerId);
    }

    private void rejectCoverUrl(String coverUrl) {
        if (StringUtils.hasText(coverUrl)) {
            throw new BusinessException("封面只允许选择站内审核资源，请使用 coverImageId");
        }
    }

    private ResponseStatusException favoriteConflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "不能重复收藏同一篇文章");
    }

    private Article findOwnedArticle(Long userId, Long articleId) {
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BusinessException("文章不存在");
        }
        if (!article.getUserId().equals(userId)) {
            throw new BusinessException("只能操作自己的文章");
        }
        return article;
    }

    private void deleteArticleWithRelatedData(Long articleId) {
        articleMapper.lockById(articleId);
        commentService.deleteByArticleId(articleId);
        textReviewService.closeDeleted(TextReviewService.ARTICLE, articleId);
        articleFavoriteMapper.delete(new QueryWrapper<ArticleFavorite>().eq("article_id", articleId));
        articleMapper.deleteById(articleId);
        redisService.deleteArticleData(articleId);
        // M15：文章被删除后，知识库索引也要移除（消费者查不到文章即按移除处理）
        sendKbIndexMessage(articleId);
    }

    private void sendPublishMessage(Article article, Long auditorId) {
        ArticlePublishMessage articlePublishMessage = new ArticlePublishMessage();
        articlePublishMessage.setArticleId(article.getId());
        articlePublishMessage.setUserId(article.getUserId());
        articlePublishMessage.setAuditorId(auditorId);
        articlePublishMessage.setTitle(article.getTitle());
        articlePublishMessage.setPublishTime(System.currentTimeMillis());
        articlePublishMessage.setMessageId(UUID.randomUUID().toString());

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ARTICLE_EXCHANGE,
                RabbitMQConfig.ARTICLE_PUBLISH_ROUTING_KEY,
                articlePublishMessage);
    }

    /**
     * 发送知识库索引消息（M15）。
     *
     * <p><b>必须在事务提交后发送。</b>如果直接在事务内发送，消费者可能在事务提交前就查到旧状态：
     * 例如审核通过时文章在库里仍是 PENDING，监听器会把它判定为「不该在知识库中」而移除索引，
     * 随后事务提交把文章改成 PUBLISHED，却不会再触发索引 —— 造成永久漏索引。
     * 因此这里注册事务同步回调，等提交完成再投递。
     */
    private void sendKbIndexMessage(Long articleId) {
        afterCommitExecutor.run(() -> doSendKbIndexMessage(articleId));
    }

    private void doSendKbIndexMessage(Long articleId) {
        KbIndexMessage message = new KbIndexMessage();
        message.setArticleId(articleId);
        message.setMessageId(UUID.randomUUID().toString());
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.ARTICLE_EXCHANGE,
                    RabbitMQConfig.KB_INDEX_ROUTING_KEY,
                    message);
        } catch (RuntimeException e) {
            // 知识库索引是增强能力：MQ 不可用时不能反过来影响发帖、下架等主流程。
            // 漏掉的消息可以在恢复后通过管理员的全量重建接口补齐。
            log.error("知识库索引消息发送失败：articleId={}", articleId, e);
        }
    }

    /**
     * 发送内容审核消息（M16）。
     *
     * <p>同样在事务提交后投递。MQ 故障不影响发帖主流程：
     * 漏掉的审核消息只会让该文章回到"纯人工审核"的原始流程，不会导致内容丢失或误放行。
     */
    private void sendModerationMessage(Long articleId) {
        afterCommitExecutor.run(() -> doSendModerationMessage(articleId));
    }

    private void doSendModerationMessage(Long articleId) {
        ModerationMessage message = new ModerationMessage();
        message.setTargetType(ModerationMessage.TARGET_ARTICLE);
        message.setTargetId(articleId);
        message.setMessageId(UUID.randomUUID().toString());
        try {
            // M18：把当前请求的轨迹 id 挂到消息头上。业务请求通常没有轨迹
            // （propagate() 会退化成不做任何事的处理器），但 AI 助手触发的写操作例外 ——
            // 那种情况下审核链路会接在同一 traceId 上，管理端能看到完整因果。
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.ARTICLE_EXCHANGE,
                    RabbitMQConfig.MODERATION_ROUTING_KEY,
                    message,
                    TraceHeaders.propagate());
        } catch (RuntimeException e) {
            log.error("内容审核消息发送失败：articleId={}", articleId, e);
        }
    }

    private void recordAudit(Long articleId, Long auditorId, String auditStatus, String reason) {
        ArticleAuditRecord record = new ArticleAuditRecord();
        record.setArticleId(articleId);
        record.setAuditorId(auditorId);
        record.setAuditStatus(auditStatus);
        record.setReason(reason);
        articleAuditRecordMapper.insert(record);
    }

    private void validateStatus(String status) {
        if (!STATUS_DRAFT.equals(status)
                && !STATUS_PENDING.equals(status)
                && !STATUS_PUBLISHED.equals(status)
                && !STATUS_REJECTED.equals(status)
                && !STATUS_OFFLINE.equals(status)) {
            throw new BusinessException("文章状态不正确");
        }
    }

    /** 只在社区阅读链调用，热榜/推荐/管理逐项读取不增加作者查询。 */
    private void fillCommunityPresentation(List<Article> articles) {
        if (articles == null || articles.isEmpty()) return;
        var authors = userService.publicAuthorSummaries(articles.stream().map(Article::getUserId).toList());
        for (Article article : articles) {
            article.setAuthor(authors.getOrDefault(article.getUserId(),
                    com.bitforum.dto.PublicAuthorSummary.unavailable(article.getUserId())));
            article.setAnswerCount(null);
        }
        List<Long> ids = articles.stream()
                .filter(a -> TYPE_QUESTION.equals(a.getContentType()) && STATUS_PUBLISHED.equals(a.getStatus()))
                .map(Article::getId).filter(Objects::nonNull).distinct().sorted().toList();
        Map<Long, Long> counts = new HashMap<>();
        for (int start = 0; start < ids.size(); start += 200) {
            for (Map<String, Object> row : commentMapper.selectMaps(new QueryWrapper<Comment>()
                    .select("article_id", "COUNT(*) AS answer_count")
                    .in("article_id", ids.subList(start, Math.min(start + 200, ids.size())))
                    .eq("status", CommentService.STATUS_PUBLISHED).eq("parent_comment_id", 0)
                    .groupBy("article_id"))) {
                Number id = (Number) row.get("article_id"), count = (Number) row.get("answer_count");
                if (id != null && count != null) counts.put(id.longValue(), count.longValue());
            }
        }
        for (Article article : articles) {
            if (ids.contains(article.getId())) article.setAnswerCount(counts.getOrDefault(article.getId(), 0L));
        }
    }

    private void fillArticleMetadata(List<Article> articles) {
        if (articles == null || articles.isEmpty()) {
            return;
        }

        Set<Long> categoryIds = articles.stream()
                .map(Article::getCategoryId)
                .filter(categoryId -> categoryId != null)
                .collect(Collectors.toSet());
        Map<Long, Category> categoriesById = categoryIds.isEmpty()
                ? Map.of()
                : categoryMapper.selectBatchIds(categoryIds).stream()
                        .collect(Collectors.toMap(Category::getId, Function.identity()));

        Set<Long> articleIds = articles.stream()
                .map(Article::getId)
                .filter(articleId -> articleId != null)
                .collect(Collectors.toSet());
        Map<Long, Long> favoriteCounts = new HashMap<>();
        if (!articleIds.isEmpty()) {
            QueryWrapper<ArticleFavorite> favoriteQuery = new QueryWrapper<>();
            favoriteQuery.select("article_id", "COUNT(*) AS favorite_count")
                    .in("article_id", articleIds)
                    .groupBy("article_id");
            for (Map<String, Object> row : articleFavoriteMapper.selectMaps(favoriteQuery)) {
                Number articleId = (Number) row.get("article_id");
                Number favoriteCount = (Number) row.get("favorite_count");
                if (articleId != null && favoriteCount != null) {
                    favoriteCounts.put(articleId.longValue(), favoriteCount.longValue());
                }
            }
        }

        Set<Long> answerIds = articles.stream().map(Article::getAcceptedAnswerId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Comment> answers = answerIds.isEmpty() ? Map.of()
                : commentMapper.selectBatchIds(answerIds).stream()
                        .collect(Collectors.toMap(Comment::getId, Function.identity()));
        for (Article article : articles) {
            if (article.getAcceptedAnswerId() != null) {
                sanitizeAcceptedAnswer(article, answers.get(article.getAcceptedAnswerId()));
            }
            Category category = categoriesById.get(article.getCategoryId());
            if (category != null) {
                article.setCategoryName(category.getName());
            article.setCategoryAvailable(Integer.valueOf(CategoryService.STATUS_ENABLED).equals(category.getStatus()));
            }
            article.setFavoriteCount(favoriteCounts.getOrDefault(article.getId(), 0L));
        }
    }

    private void fillArticleMetadata(Article article) {
        if (article == null) {
            return;
        }
        fillCategoryName(article);
        fillFavoriteCount(article);
        if (article.getAcceptedAnswerId() != null) {
            sanitizeAcceptedAnswer(article, commentMapper.selectById(article.getAcceptedAnswerId()));
        }
    }

    private void sanitizeAcceptedAnswer(Article article, Comment answer) {
        if (!TYPE_QUESTION.equals(article.getContentType()) || answer == null
                || !article.getId().equals(answer.getArticleId())
                || !CommentService.STATUS_PUBLISHED.equals(answer.getStatus())
                || !Long.valueOf(0).equals(answer.getParentCommentId())) {
            article.setAcceptedAnswerId(null);
        }
    }

    private void fillCategoryName(Article article) {
        if (article == null || article.getCategoryId() == null) {
            return;
        }
        Category category = categoryMapper.selectById(article.getCategoryId());
        if (category != null) {
            article.setCategoryName(category.getName());
            article.setCategoryAvailable(Integer.valueOf(CategoryService.STATUS_ENABLED).equals(category.getStatus()));
        }
    }

    private void fillFavoriteCount(Article article) {
        if (article == null || article.getId() == null) {
            return;
        }
        Long count = articleFavoriteMapper.selectCount(
                new QueryWrapper<ArticleFavorite>().eq("article_id", article.getId()));
        article.setFavoriteCount(count);
    }
}
