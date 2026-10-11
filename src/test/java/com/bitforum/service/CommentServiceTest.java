package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bitforum.entity.Article;
import com.bitforum.entity.Category;
import com.bitforum.entity.Comment;
import com.bitforum.entity.Notification;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CategoryMapper;
import com.bitforum.mapper.CommentMapper;
import com.bitforum.mapper.NotificationMapper;

@SpringBootTest
// 测试结束后自动回滚数据库，避免测试评论或测试文章污染本地数据
@Transactional
public class CommentServiceTest {
    @Autowired
    private CommentService commentService;
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private NotificationMapper notificationMapper;

    @Test
    void publishShouldFailWhenArticleNotExists() {
        Long userId = 30001L;
        Long notExistsArticle = 99999999L;
        String content = "不存在文章评论测试-" + UUID.randomUUID();

        // 先确认这个 articleId 在数据库里真的不存在，测试前提要明确
        assertNull(articleMapper.selectById(notExistsArticle));

        boolean success = commentService.publish(userId, notExistsArticle, content);

        // CommentService.publish 遇到不存在的文章时应该返回 false，让 Controller 返回业务失败提示
        assertFalse(success);

        // 再查 comment 表，确认没有因为异常场景插入脏评论
        QueryWrapper<Comment> wrapper = new QueryWrapper<>();
        wrapper.eq("article_id", notExistsArticle);
        wrapper.eq("content", content);

        assertEquals(0L, commentMapper.selectCount(wrapper));
    }

    @Test
    void publishOtherUsersArticleCommentShouldCreateNotification() {
        Article article = createArticle(41001L, ArticleService.STATUS_PUBLISHED);

        boolean success = commentService.publish(41002L, article.getId(), "M4 评论通知");

        assertTrue(success);
        Notification notification = notificationMapper.selectOne(new QueryWrapper<Notification>()
                .eq("article_id", article.getId())
                .eq("type", NotificationService.TYPE_COMMENT)
                .last("LIMIT 1"));
        assertNotNull(notification);
        assertEquals(41001L, notification.getReceiverId());
        assertEquals(41002L, notification.getSenderId());
    }

    @Test
    void publishOwnArticleCommentShouldNotCreateNotification() {
        Article article = createArticle(41003L, ArticleService.STATUS_PUBLISHED);

        boolean success = commentService.publish(41003L, article.getId(), "M4 自评不通知");

        assertTrue(success);
        Long count = notificationMapper.selectCount(new QueryWrapper<Notification>()
                .eq("article_id", article.getId())
                .eq("type", NotificationService.TYPE_COMMENT));
        assertEquals(0L, count);
    }

    @Test
    void publishCommentShouldFailWhenArticleIsNotPublished() {
        Article article = createArticle(41004L, ArticleService.STATUS_DRAFT);

        boolean success = commentService.publish(41005L, article.getId(), "M4 草稿评论失败");

        assertFalse(success);
        Long count = commentMapper.selectCount(new QueryWrapper<Comment>()
                .eq("article_id", article.getId()));
        assertEquals(0L, count);
    }

    @Test
    void adminShouldDeleteExistingComment() {
        Comment comment = new Comment();
        comment.setArticleId(40001L);
        comment.setUserId(40002L);
        comment.setContent("管理员删除评论测试-" + UUID.randomUUID());
        comment.setParentCommentId(0L);
        commentMapper.insert(comment);

        boolean deleted = commentService.deleteByAdmin(comment.getId());

        assertTrue(deleted);
        // 管理员删除评论后，comment 表中不应该还能查到这条记录。
        assertNull(commentMapper.selectById(comment.getId()));
    }

    @Test
    void adminDeleteShouldReturnFalseWhenCommentNotExists() {
        Long notExistsCommentId = 99999999L;

        assertNull(commentMapper.selectById(notExistsCommentId));

        boolean deleted = commentService.deleteByAdmin(notExistsCommentId);

        assertFalse(deleted);
    }

    private Article createArticle(Long userId, String status) {
        Category category = categoryMapper.selectList(null).get(0);
        Article article = new Article();
        article.setTitle("M4-cmt-" + UUID.randomUUID());
        article.setContent("M4 评论通知测试文章");
        article.setUserId(userId);
        article.setCategoryId(category.getId());
        article.setStatus(status);
        articleMapper.insert(article);
        return article;
    }
}
