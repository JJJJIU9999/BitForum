package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.entity.Article;
import com.bitforum.entity.Notification;
import com.bitforum.mapper.NotificationMapper;

@SpringBootTest
@Transactional
class NotificationServiceTest {
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private NotificationMapper notificationMapper;

    @Test
    void pageNotificationsShouldOnlyReturnCurrentUsersNotifications() {
        Notification first = insertNotification(51001L, 52001L, NotificationService.TYPE_COMMENT, 1L);
        insertNotification(51002L, 52002L, NotificationService.TYPE_LIKE, 2L);

        Page<Notification> page = notificationService.pageNotifications(51001L, 1, 10);

        assertEquals(1, page.getRecords().size());
        assertEquals(first.getId(), page.getRecords().get(0).getId());
    }

    @Test
    void countUnreadShouldOnlyCountCurrentUsersUnreadNotifications() {
        insertNotification(51003L, 52003L, NotificationService.TYPE_COMMENT, 3L);
        Notification read = insertNotification(51003L, 52004L, NotificationService.TYPE_LIKE, 4L);
        read.setReadStatus(NotificationService.READ_STATUS_READ);
        notificationMapper.updateById(read);
        insertNotification(51004L, 52005L, NotificationService.TYPE_FAVORITE, 5L);

        Long count = notificationService.countUnread(51003L);

        assertEquals(1L, count);
    }

    @Test
    void markReadShouldOnlyAllowCurrentUsersNotification() {
        Notification own = insertNotification(51005L, 52006L, NotificationService.TYPE_COMMENT, 6L);
        Notification other = insertNotification(51006L, 52007L, NotificationService.TYPE_LIKE, 7L);

        notificationService.markRead(51005L, own.getId());

        assertEquals(NotificationService.READ_STATUS_READ, notificationMapper.selectById(own.getId()).getReadStatus());
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            notificationService.markRead(51005L, other.getId());
        });
        assertEquals("通知不存在或不属于当前用户", exception.getMessage());
        assertEquals(NotificationService.READ_STATUS_UNREAD,
                notificationMapper.selectById(other.getId()).getReadStatus());
    }

    @Test
    void markAllReadShouldOnlyAffectCurrentUser() {
        Notification ownFirst = insertNotification(51007L, 52008L, NotificationService.TYPE_COMMENT, 8L);
        Notification ownSecond = insertNotification(51007L, 52009L, NotificationService.TYPE_FAVORITE, 9L);
        Notification other = insertNotification(51008L, 52010L, NotificationService.TYPE_LIKE, 10L);

        notificationService.markAllRead(51007L);

        List<Notification> ownNotifications = notificationMapper.selectList(new QueryWrapper<Notification>()
                .eq("receiver_id", 51007L));
        assertTrue(ownNotifications.stream()
                .allMatch(notification -> notification.getReadStatus() == NotificationService.READ_STATUS_READ));
        assertEquals(NotificationService.READ_STATUS_READ, notificationMapper.selectById(ownFirst.getId()).getReadStatus());
        assertEquals(NotificationService.READ_STATUS_READ, notificationMapper.selectById(ownSecond.getId()).getReadStatus());
        assertEquals(NotificationService.READ_STATUS_UNREAD, notificationMapper.selectById(other.getId()).getReadStatus());
    }

    @Test
    void auditApprovedNotificationShouldBeIdempotentBySourceMessage() {
        Article article = new Article();
        article.setId(100L);
        article.setUserId(51009L);
        article.setTitle("幂等通知测试");

        notificationService.notifyAuditApproved(article, 52011L, "message-100");
        notificationService.notifyAuditApproved(article, 52011L, "message-100");

        List<Notification> notifications = notificationMapper.selectList(new QueryWrapper<Notification>()
                .eq("source_message_id", "message-100"));
        assertEquals(1, notifications.size());
        assertEquals(NotificationService.TYPE_AUDIT_APPROVED, notifications.get(0).getType());
    }

    private Notification insertNotification(Long receiverId, Long senderId, String type, Long articleId) {
        Notification notification = new Notification();
        notification.setReceiverId(receiverId);
        notification.setSenderId(senderId);
        notification.setType(type);
        notification.setTitle("M4 通知测试");
        notification.setContent("M4 通知内容");
        notification.setArticleId(articleId);
        notification.setReadStatus(NotificationService.READ_STATUS_UNREAD);
        notificationMapper.insert(notification);
        return notification;
    }
}

