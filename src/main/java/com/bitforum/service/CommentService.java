package com.bitforum.service;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.ai.trace.TraceHeaders;
import com.bitforum.config.RabbitMQConfig;
import com.bitforum.entity.Article;
import com.bitforum.entity.Comment;
import com.bitforum.mapper.AnswerLikeMapper;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CommentMapper;
import com.bitforum.message.AfterCommitExecutor;
import com.bitforum.message.ModerationMessage;

@Service
public class CommentService {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_REJECTED = "REJECTED";

    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private AnswerLikeMapper answerLikeMapper;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AfterCommitExecutor afterCommitExecutor;
    @Autowired
    private TextReviewService textReviewService;
    @Autowired
    private UserService userService;

    private static final Logger log = LoggerFactory.getLogger(CommentService.class);

    //发表评论
    @Transactional
    public boolean publish(Long userId, Long articleId, String content) {
        return publish(userId, articleId, content, 0L);
    }

    @Transactional
    public boolean publish(Long userId, Long articleId, String content, Long parentCommentId) {
        requirePositive(articleId);
        long parentId = parentCommentId == null ? 0L : parentCommentId;
        if (parentId < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "父评论ID不能为负数");
        // 评论是文章的从属数据，插入评论前必须先确认文章存在
        // 这里直接用 ArticleMapper 查询，避免 CommentService 和 ArticleService 互相注入形成循环依赖
        Article article = articleMapper.lockById(articleId);
        if (article == null || !ArticleService.STATUS_PUBLISHED.equals(article.getStatus())) {
            // 这里先返回 false，让 Controller 决定给前端什么业务提示
            if (parentId != 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "回复目标不存在");
            return false;
        }
        if (parentId != 0) requireReplyParent(articleId, parentId, HttpStatus.NOT_FOUND);
        Comment comment = new Comment();
        comment.setUserId(userId);
        comment.setArticleId(articleId);
        comment.setContent(content);
        comment.setStatus(STATUS_PENDING);
        comment.setParentCommentId(parentId); // 0 表示顶层，正数只允许指向公开顶层评论
        commentMapper.insert(comment);
        boolean held = textReviewService.recordIfMatched(TextReviewService.COMMENT,
                comment.getId(), content, "NEW", null, null);
        if (!held) {
            comment.setStatus(STATUS_PUBLISHED);
            commentMapper.updateById(comment);
            if (parentId == 0) notificationService.notifyComment(article, userId, content);
            else notificationService.notifyReply(comment.getId());
        }
        log.info("评论已提交：commentId={}, status={}", comment.getId(), comment.getStatus());
        // AI 仍是辅助意见；命中文字规则时评论保持待审，不能由 AI 自动公开。
        sendModerationMessage(comment.getId());
        // 插入成功后返回 true，表示 Controller 可以返回“评论发布成功”
        return true;
    }

    /**
     * 发送评论审核消息（M16）。
     *
     * <p>事务提交后投递；MQ 故障不影响评论发布 —— 漏掉的消息只意味着该评论
     * 回到"没有 AI 检测"的状态，由用户举报等既有机制兜底。
     */
    private void sendModerationMessage(Long commentId) {
        afterCommitExecutor.run(() -> doSendModerationMessage(commentId));
    }

    private void doSendModerationMessage(Long commentId) {
        ModerationMessage message = new ModerationMessage();
        message.setTargetType(ModerationMessage.TARGET_COMMENT);
        message.setTargetId(commentId);
        message.setMessageId(UUID.randomUUID().toString());
        try {
            // M18：与文章审核消息同样的处理：若当前线程有执行轨迹，就把 traceId 带上
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.ARTICLE_EXCHANGE,
                    RabbitMQConfig.MODERATION_ROUTING_KEY,
                    message,
                    TraceHeaders.propagate());
        } catch (RuntimeException e) {
            log.error("评论审核消息发送失败：commentId={}", commentId, e);
        }
    }

    // 旧三参数发布/旧列表的调用契约保留；扩展字段不入 comment 表。
    public List<Comment> listByArticleId(Long articleId) {
        return listByArticleId(articleId, null, null);
    }

    @Transactional(readOnly = true)
    public List<Comment> listByArticleId(Long articleId, Long currentUserId, String sort) {
        requirePositive(articleId);
        if (sort != null && !Set.of("hot", "latest").contains(sort)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知排序方式");
        }
        Article article = articleMapper.selectById(articleId);
        if (article == null || !ArticleService.STATUS_PUBLISHED.equals(article.getStatus())) return List.of();
        List<Comment> rows = commentMapper.selectList(new QueryWrapper<Comment>()
                .eq("article_id", articleId).eq("status", STATUS_PUBLISHED)
                .orderByAsc("create_time", "id"));
        Map<Long, Comment> roots = rows.stream().filter(CommentService::isTopLevel)
                .collect(Collectors.toMap(Comment::getId, Function.identity()));
        List<Comment> visible = rows.stream()
                .filter(c -> isTopLevel(c) || roots.containsKey(c.getParentCommentId())).toList();
        fillAuthors(visible);
        if (ArticleService.TYPE_QUESTION.equals(article.getContentType()) && !roots.isEmpty()) {
            for (var stats : answerLikeMapper.stats(roots.keySet(), currentUserId)) {
                Comment root = roots.get(stats.getCommentId());
                root.setLikeCount(stats.getLikeCount());
                root.setLikedByCurrentUser(stats.isLikedByCurrentUser());
            }
        }
        if (sort == null || !ArticleService.TYPE_QUESTION.equals(article.getContentType())) return visible;
        Comparator<Comment> newest = Comparator.comparing(Comment::getCreateTime,
                Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Comment::getId, Comparator.reverseOrder());
        Comparator<Comment> order = "hot".equals(sort)
                ? Comparator.comparingLong(Comment::getLikeCount).reversed().thenComparing(newest) : newest;
        order = Comparator.comparing((Comment c) -> !Objects.equals(c.getId(), article.getAcceptedAnswerId()))
                .thenComparing(order);
        List<Comment> result = new ArrayList<>();
        for (Comment root : roots.values().stream().sorted(order).toList()) {
            result.add(root);
            visible.stream().filter(c -> root.getId().equals(c.getParentCommentId())).forEach(result::add);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<Comment> listOwnByArticleId(Long userId, Long articleId) {
        requirePositive(articleId);
        Article article = articleMapper.selectById(articleId);
        if (article == null || !ArticleService.STATUS_PUBLISHED.equals(article.getStatus())) return List.of();
        Set<Long> publicRoots = commentMapper.selectList(new QueryWrapper<Comment>()
                .eq("article_id", articleId).eq("parent_comment_id", 0).eq("status", STATUS_PUBLISHED))
                .stream().map(Comment::getId).collect(Collectors.toSet());
        List<Comment> visible = commentMapper.selectList(new QueryWrapper<Comment>()
                .eq("user_id", userId).eq("article_id", articleId).orderByAsc("create_time", "id"))
                .stream().filter(c -> isTopLevel(c) || publicRoots.contains(c.getParentCommentId())).toList();
        fillAuthors(visible);
        return visible;
    }

    private void fillAuthors(List<Comment> comments) {
        if (comments.isEmpty()) return;
        var authors = userService.publicAuthorSummaries(comments.stream().map(Comment::getUserId).toList());
        for (Comment comment : comments) comment.setAuthor(authors.getOrDefault(comment.getUserId(),
                com.bitforum.dto.PublicAuthorSummary.unavailable(comment.getUserId())));
    }

    @Transactional
    public void likeAnswer(Long userId, Long commentId) {
        requirePublicAnswer(commentId);
        // 文章锁串行化同文章的状态变化；数据库唯一约束仍是最终保证。
        answerLikeMapper.insertOnce(commentId, userId);
    }

    @Transactional
    public void unlikeAnswer(Long userId, Long commentId) {
        requirePublicAnswer(commentId);
        answerLikeMapper.delete(new QueryWrapper<com.bitforum.entity.AnswerLike>()
                .eq("comment_id", commentId).eq("user_id", userId));
    }

    private Comment requirePublicAnswer(Long commentId) {
        requirePositive(commentId);
        Comment snapshot = commentMapper.selectById(commentId);
        if (snapshot == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "回答不存在");
        Article article = articleMapper.lockById(snapshot.getArticleId());
        Comment answer = commentMapper.lockById(commentId);
        if (article == null || !ArticleService.STATUS_PUBLISHED.equals(article.getStatus())
                || answer == null || !STATUS_PUBLISHED.equals(answer.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "回答不存在");
        }
        if (!ArticleService.TYPE_QUESTION.equals(article.getContentType()) || !isTopLevel(answer)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能点赞问题的顶层回答");
        }
        return answer;
    }

    private Comment requireReplyParent(Long articleId, Long parentId, HttpStatus hiddenStatus) {
        Comment parent = commentMapper.lockById(parentId);
        if (parent == null || !STATUS_PUBLISHED.equals(parent.getStatus())) {
            throw new ResponseStatusException(hiddenStatus, "回复目标不存在或未公开");
        }
        if (!articleId.equals(parent.getArticleId()) || !isTopLevel(parent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能回复本文章的顶层评论");
        }
        return parent;
    }

    public static boolean isTopLevel(Comment comment) {
        return Long.valueOf(0L).equals(comment.getParentCommentId());
    }

    private static void requirePositive(Long id) {
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID必须为正整数");
    }

    @Transactional
    public void deleteByArticleId(Long articleId) {
        articleMapper.lockById(articleId);
        deleteComments(commentMapper.selectList(new QueryWrapper<Comment>().eq("article_id", articleId)
                .orderByAsc("id").last("FOR UPDATE")));
    }

    private void deleteComments(List<Comment> comments) {
        if (comments.isEmpty()) return;
        List<Long> ids = comments.stream().map(Comment::getId).toList();
        for (Comment comment : comments) textReviewService.closeDeleted(TextReviewService.COMMENT, comment.getId());
        answerLikeMapper.delete(new QueryWrapper<com.bitforum.entity.AnswerLike>().in("comment_id", ids));
        commentMapper.delete(new QueryWrapper<Comment>().in("id", ids));
    }

    @Transactional
    public void holdForTextReview(Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) return;
        articleMapper.lockById(comment.getArticleId());
        comment = commentMapper.lockById(commentId);
        if (comment != null && STATUS_PUBLISHED.equals(comment.getStatus())) {
            articleMapper.clearAcceptedAnswer(comment.getArticleId(), commentId);
            comment.setStatus(STATUS_PENDING);
            commentMapper.updateById(comment);
        }
    }

    public Page<Comment> pageComments(long pageNum, long pageSize) {
        // 管理端查看全部评论，不按某篇文章过滤；按时间倒序更方便优先处理新评论。
        Page<Comment> page = new Page<>(pageNum, pageSize);
        QueryWrapper<Comment> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc("create_time");
        return commentMapper.selectPage(page, wrapper);
    }

    @Transactional
    public boolean deleteByAdmin(Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            return false;
        }
        articleMapper.lockById(comment.getArticleId());
        comment = commentMapper.lockById(commentId);
        if (comment == null) return false;
        articleMapper.clearAcceptedAnswer(comment.getArticleId(), commentId);
        List<Comment> removed = new ArrayList<>();
        if (isTopLevel(comment)) {
            removed.addAll(commentMapper.selectList(new QueryWrapper<Comment>()
                    .eq("article_id", comment.getArticleId()).eq("parent_comment_id", commentId).orderByAsc("id").last("FOR UPDATE")));
        }
        removed.add(comment);
        deleteComments(removed);
        return true;
    }
}
