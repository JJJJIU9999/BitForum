package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bitforum.entity.*;
import com.bitforum.mapper.*;
import com.bitforum.util.JwtUtil;
import com.zaxxer.hikari.HikariDataSource;

/** 必须显式选择 C-M22 独立 Compose。没有类级回滚事务，并发使用不同真实连接。 */
@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "CM22_ISOLATED_TESTS", matches = "true")
class Cm22IntegrationTest {
    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (url == null || !url.startsWith("jdbc:mysql://127.0.0.1:23306/bitforum_cm22_test?")) {
            throw new IllegalStateException("C-M22 tests require their dedicated Compose database");
        }
        registry.add("spring.datasource.url", () -> url);
    }

    @Autowired CommentService comments;
    @Autowired ArticleService articles;
    @Autowired TextRuleService rules;
    @Autowired TextReviewService reviews;
    @Autowired NotificationService notifications;
    @Autowired CommentMapper commentMapper;
    @Autowired ArticleMapper articleMapper;
    @Autowired AnswerLikeMapper likes;
    @Autowired NotificationMapper notificationMapper;
    @Autowired TextReviewRecordMapper reviewMapper;
    @Autowired UserMapper users;
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean RabbitTemplate rabbit;
    @MockitoBean RedisService redis;
    private User author, reader, admin;
    private Article question;

    @BeforeEach
    void fixture() {
        author = user("USER"); reader = user("USER"); admin = user("ADMIN");
        question = article("QUESTION");
    }

    @Test
    void threeArgumentPublishAndArticleCommentsRemainCompatible() {
        assertFalse(comments.publish(author.getId(), Long.MAX_VALUE, "missing"));
        assertTrue(comments.publish(reader.getId(), question.getId(), "legacy answer"));
        Comment answer = newest(question.getId());
        assertEquals(0L, answer.getParentCommentId());
        assertEquals(1, comments.listByArticleId(question.getId()).size());
        Notification event = notificationMapper.selectOne(new QueryWrapper<Notification>().eq("article_id", question.getId()));
        assertEquals(author.getId(), event.getReceiverId());
        assertEquals("收到新回答", event.getTitle());
        Article article = article("ARTICLE");
        assertTrue(comments.publish(reader.getId(), article.getId(), "old article comment"));
        assertEquals("收到新评论", notificationMapper.selectOne(new QueryWrapper<Notification>().eq("article_id", article.getId())).getTitle());
    }

    @Test
    void concurrentLikesAndUnlikesUseIndependentConnectionsAndPermitSelfLike() throws Exception {
        Comment answer = comment(question, reader, 0L, "PUBLISHED", "answer");
        Set<Long> connectionIds = ConcurrentHashMap.newKeySet();
        race(6, () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            connectionIds.add(new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject("SELECT CONNECTION_ID()", Long.class));
            comments.likeAnswer(reader.getId(), answer.getId());
        }));
        assertTrue(connectionIds.size() > 1, "must use independent MySQL connections");
        assertEquals(1, likeCount(answer.getId()));
        assertEquals(0, notificationMapper.selectCount(new QueryWrapper<Notification>().eq("article_id", question.getId())));
        Comment view = comments.listByArticleId(question.getId(), reader.getId(), "hot").get(0);
        assertEquals(1, view.getLikeCount()); assertTrue(view.isLikedByCurrentUser());
        assertFalse(comments.listByArticleId(question.getId(), null, "hot").get(0).isLikedByCurrentUser());
        race(6, () -> comments.unlikeAnswer(reader.getId(), answer.getId()));
        assertEquals(0, likeCount(answer.getId()));
    }

    @Test
    void databaseUniqueConstraintProtectsDirectConcurrentInserts() throws Exception {
        Comment answer = comment(question, reader, 0L, "PUBLISHED", "answer");
        race(6, () -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> likes.insertOnce(answer.getId(), reader.getId())));
        assertEquals(1, likeCount(answer.getId()));
    }

    @Test
    void hotLatestAndAcceptedOrderAreStableAndChildrenRemainChronological() {
        Comment first = comment(question, reader, 0L, "PUBLISHED", "first");
        Comment second = comment(question, reader, 0L, "PUBLISHED", "second");
        Comment accepted = comment(question, reader, 0L, "PUBLISHED", "accepted");
        Comment child1 = comment(question, author, second.getId(), "PUBLISHED", "child1");
        Comment child2 = comment(question, author, second.getId(), "PUBLISHED", "child2");
        comments.likeAnswer(author.getId(), first.getId());
        articles.acceptAnswer(author.getId(), question.getId(), accepted.getId());
        assertEquals(List.of(accepted.getId(), first.getId(), second.getId(), child1.getId(), child2.getId()),
                ids(comments.listByArticleId(question.getId(), null, "hot")));
        assertEquals(List.of(accepted.getId(), second.getId(), child1.getId(), child2.getId(), first.getId()),
                ids(comments.listByArticleId(question.getId(), null, "latest")));
        assertEquals(List.of(first.getId(), second.getId(), accepted.getId(), child1.getId(), child2.getId()),
                ids(comments.listByArticleId(question.getId())));
    }

    @Test
    void publishedReplyHasNeutralIdempotentNotificationOnlyToParentAuthor() throws Exception {
        Comment answer = comment(question, reader, 0L, "PUBLISHED", "answer");
        assertTrue(comments.publish(author.getId(), question.getId(), "secret reply body", answer.getId()));
        Comment reply = newest(question.getId());
        Notification event = event(reply);
        assertEquals(reader.getId(), event.getReceiverId());
        assertEquals("收到新回复", event.getTitle());
        assertFalse(event.getContent().contains("secret reply body"));
        race(6, () -> notifications.notifyReply(reply.getId()));
        assertEquals(1, eventCount(reply));
        assertTrue(comments.publish(author.getId(), question.getId(), "second", answer.getId()));
        Comment next = newest(question.getId());
        assertEquals(1, eventCount(next)); assertNotEquals(event(reply).getSourceMessageId(), event(next).getSourceMessageId());
    }

    @Test
    void selfReplyProducesNoNotification() {
        Comment answer = comment(question, reader, 0L, "PUBLISHED", "answer");
        comments.publish(reader.getId(), question.getId(), "self reply", answer.getId());
        assertEquals(0, eventCount(newest(question.getId())));
    }

    @Test
    void heldReplyNotifiesOnlyAfterApprovalAndRescanDoesNotNotifyAgain() {
        Comment answer = comment(question, reader, 0L, "PUBLISHED", "answer");
        Comment reply = heldReply(answer);
        assertEquals(0, eventCount(reply));
        assertFalse(ids(comments.listByArticleId(question.getId())).contains(reply.getId()));
        assertTrue(ids(comments.listOwnByArticleId(author.getId(), question.getId())).contains(reply.getId()));
        assertFalse(ids(comments.listOwnByArticleId(reader.getId(), question.getId())).contains(reply.getId()));
        TextReviewRecord review = review(reply);
        reviews.decide(review.getId(), admin.getId(), true, null);
        assertEquals(1, eventCount(reply));
        assertEquals("PUBLISHED", commentMapper.selectById(reply.getId()).getStatus());
        comments.holdForTextReview(reply.getId());
        rules.create("cm22-version-" + UUID.randomUUID(), admin.getId());
        assertTrue(reviews.recordIfMatched("COMMENT", reply.getId(), reply.getContent(), "RESCAN", null, null));
        reviews.decide(review(reply).getId(), admin.getId(), true, null);
        assertEquals(1, eventCount(reply));
    }

    @Test
    void hiddenParentBlocksApprovalAndOwnViewsThenRestorationRequiresExplicitApproval() {
        Comment answer = comment(question, reader, 0L, "PUBLISHED", "answer");
        Comment publicChild = comment(question, author, answer.getId(), "PUBLISHED", "public child");
        Comment held = heldReply(answer);
        comments.likeAnswer(author.getId(), answer.getId());
        comments.holdForTextReview(answer.getId());
        assertTrue(comments.listByArticleId(question.getId()).isEmpty());
        assertFalse(ids(comments.listOwnByArticleId(author.getId(), question.getId())).contains(held.getId()));
        expectStatus(409, () -> reviews.decide(review(held).getId(), admin.getId(), true, null));
        assertEquals("PENDING", commentMapper.selectById(held.getId()).getStatus());
        assertEquals("PENDING", review(held).getStatus()); assertEquals(0, eventCount(held));
        assertNotNull(commentMapper.selectById(publicChild.getId())); assertEquals(1, likeCount(answer.getId()));
        answer.setStatus("PUBLISHED"); commentMapper.updateById(answer);
        assertTrue(ids(comments.listByArticleId(question.getId())).contains(publicChild.getId()));
        assertFalse(ids(comments.listByArticleId(question.getId())).contains(held.getId()));
        reviews.decide(review(held).getId(), admin.getId(), true, null);
        assertEquals(1, eventCount(held));
        assertNull(articleMapper.selectById(question.getId()).getAcceptedAnswerId());
    }

    @Test
    void hiddenArticleBlocksApprovalAndAllCommentViews() {
        Comment answer = comment(question, reader, 0L, "PUBLISHED", "answer");
        Comment held = heldReply(answer);
        question.setStatus("OFFLINE"); articleMapper.updateById(question);
        assertTrue(comments.listByArticleId(question.getId()).isEmpty());
        assertTrue(comments.listOwnByArticleId(author.getId(), question.getId()).isEmpty());
        expectStatus(409, () -> reviews.decide(review(held).getId(), admin.getId(), true, null));
        assertEquals(0, eventCount(held));
    }

    @Test
    void rootDeletionClearsAllChildStatesLikesPendingReviewsAndAcceptanceButKeepsEvents() {
        Comment answer = comment(question, reader, 0L, "PUBLISHED", "answer");
        Comment sibling = comment(question, reader, 0L, "PUBLISHED", "sibling");
        comments.publish(author.getId(), question.getId(), "visible", answer.getId());
        Comment visible = newest(question.getId());
        Comment held = heldReply(answer);
        Comment rejected = heldReply(answer);
        reviews.decide(review(rejected).getId(), admin.getId(), false, "reject");
        String rejectState = review(rejected).getStatus();
        comments.likeAnswer(reader.getId(), answer.getId());
        articles.acceptAnswer(author.getId(), question.getId(), answer.getId());
        assertTrue(comments.deleteByAdmin(answer.getId()));
        for (Comment target : List.of(answer, visible, held, rejected)) assertNull(commentMapper.selectById(target.getId()));
        assertNotNull(commentMapper.selectById(sibling.getId())); assertEquals(0, likeCount(answer.getId()));
        assertEquals("SUPERSEDED", review(held).getStatus()); assertEquals(rejectState, review(rejected).getStatus());
        assertNull(articleMapper.selectById(question.getId()).getAcceptedAnswerId());
        assertEquals(1, eventCount(visible));
        expectStatus(409, () -> reviews.decide(review(held).getId(), admin.getId(), true, null));
    }

    @Test
    void deletingOneChildPreservesParentSiblingLikesAndAcceptedAnswer() {
        Comment answer = comment(question, reader, 0L, "PUBLISHED", "answer");
        Comment held = heldReply(answer);
        Comment sibling = comment(question, author, answer.getId(), "PUBLISHED", "sibling");
        comments.likeAnswer(reader.getId(), answer.getId());
        articles.acceptAnswer(author.getId(), question.getId(), answer.getId());
        comments.deleteByAdmin(held.getId());
        assertNotNull(commentMapper.selectById(answer.getId())); assertNotNull(commentMapper.selectById(sibling.getId()));
        assertEquals(1, likeCount(answer.getId()));
        assertEquals(answer.getId(), articleMapper.selectById(question.getId()).getAcceptedAnswerId());
        assertEquals("SUPERSEDED", review(held).getStatus());
    }

    @Test
    void articleDeletionClosesAllPendingReviewsAndLikes() {
        Comment answer = comment(question, reader, 0L, "PUBLISHED", "answer");
        Comment held = heldReply(answer);
        comments.likeAnswer(reader.getId(), answer.getId());
        articles.deleteByAdmin(question.getId());
        assertEquals(0, commentMapper.selectCount(new QueryWrapper<Comment>().eq("article_id", question.getId())));
        assertEquals(0, likeCount(answer.getId())); assertEquals("SUPERSEDED", review(held).getStatus());
    }

    @Test
    void deletionFailureRollsBackAllChildLikesReviewsAndAcceptance() {
        Comment answer = comment(question, reader, 0L, "PUBLISHED", "answer");
        Comment held = heldReply(answer);
        comments.likeAnswer(reader.getId(), answer.getId());
        articles.acceptAnswer(author.getId(), question.getId(), answer.getId());
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            comments.deleteByAdmin(answer.getId());
            throw new IllegalStateException("inject outer transaction failure");
        }));
        assertNotNull(commentMapper.selectById(answer.getId())); assertNotNull(commentMapper.selectById(held.getId()));
        assertEquals(1, likeCount(answer.getId())); assertEquals("PENDING", review(held).getStatus());
        assertEquals(answer.getId(), articleMapper.selectById(question.getId()).getAcceptedAnswerId());
    }

    @Test
    void notificationFailureRollsBackReplyApprovalAndPendingReview() {
        Comment answer = comment(question, reader, 0L, "PUBLISHED", "answer");
        Comment held = heldReply(answer);
        Notification collision = new Notification();
        collision.setReceiverId(author.getId()); collision.setSenderId(reader.getId()); collision.setType("LIKE");
        collision.setTitle("collision"); collision.setContent("neutral synthetic collision"); collision.setSourceMessageId("comment-reply:" + held.getId());
        notificationMapper.insert(collision);
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> reviews.decide(review(held).getId(), admin.getId(), true, null));
        assertEquals("PENDING", commentMapper.selectById(held.getId()).getStatus());
        assertEquals("PENDING", review(held).getStatus()); assertEquals(1, eventCount(held));
    }

    @Test
    void racesAgainstDeletionLeaveNoOrphanLikesRepliesOrPendingReviews() throws Exception {
        for (int i = 0; i < 5; i++) {
            Comment answer = comment(question, reader, 0L, "PUBLISHED", "race");
            Comment held = heldReply(answer);
            raceActions(() -> {
                try { comments.likeAnswer(reader.getId(), answer.getId()); }
                catch (ResponseStatusException e) { assertEquals(404, e.getStatusCode().value()); }
            }, () -> {
                try { reviews.decide(review(held).getId(), admin.getId(), true, null); }
                catch (ResponseStatusException e) { assertEquals(409, e.getStatusCode().value()); }
            }, () -> comments.deleteByAdmin(answer.getId()));
            assertNull(commentMapper.selectById(answer.getId())); assertNull(commentMapper.selectById(held.getId()));
            assertEquals(0, likeCount(answer.getId())); assertNotEquals("PENDING", review(held).getStatus());
        }
    }

    @Test
    void racesAgainstHidingNeverExposeChildrenOrAllowNotificationAfterBlockedApproval() throws Exception {
        for (int i = 0; i < 5; i++) {
            Comment answer = comment(question, reader, 0L, "PUBLISHED", "race hide");
            Comment held = heldReply(answer);
            raceActions(() -> {
                try { comments.likeAnswer(reader.getId(), answer.getId()); }
                catch (ResponseStatusException e) { assertEquals(404, e.getStatusCode().value()); }
            }, () -> {
                try { reviews.decide(review(held).getId(), admin.getId(), true, null); }
                catch (ResponseStatusException e) { assertEquals(409, e.getStatusCode().value()); }
            }, () -> comments.holdForTextReview(answer.getId()));
            assertFalse(ids(comments.listByArticleId(question.getId())).contains(held.getId()));
            assertFalse(ids(comments.listOwnByArticleId(author.getId(), question.getId())).contains(held.getId()));
            assertEquals("PENDING".equals(commentMapper.selectById(held.getId()).getStatus()) ? 0 : 1, eventCount(held));
        }
    }

    @Test
    void deletionIncludesChildrenCommittedAfterTransactionSnapshot() throws Exception {
        Comment answer = comment(question, reader, 0L, "PUBLISHED", "snapshot root");
        String phrase = "cm22-snapshot-" + UUID.randomUUID(); rules.create(phrase, admin.getId());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                assertNotNull(commentMapper.selectById(answer.getId())); // 建立旧的一致性读快照。
                try { pool.submit(() -> comments.publish(author.getId(), question.getId(), phrase, answer.getId())).get(10, TimeUnit.SECONDS); }
                catch (Exception e) { throw new IllegalStateException(e); }
                assertTrue(comments.deleteByAdmin(answer.getId()));
            });
            assertEquals(0, commentMapper.selectCount(new QueryWrapper<Comment>().eq("article_id", question.getId())));
            TextReviewRecord record = reviewMapper.selectOne(new QueryWrapper<TextReviewRecord>().eq("content_hash", TextReviewService.fingerprint(phrase)));
            assertEquals("SUPERSEDED", record.getStatus());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void articleDeletionIncludesAnswersCommittedAfterTransactionSnapshot() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                assertNotNull(articleMapper.selectById(question.getId()));
                try { pool.submit(() -> comments.publish(reader.getId(), question.getId(), "later committed answer")).get(10, TimeUnit.SECONDS); }
                catch (Exception e) { throw new IllegalStateException(e); }
                assertTrue(articles.deleteByAdmin(question.getId()));
            });
            assertNull(articleMapper.selectById(question.getId()));
            assertEquals(0, commentMapper.selectCount(new QueryWrapper<Comment>().eq("article_id", question.getId())));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void apiMatchesHttpCodesAndRejectsInvalidParentsAndUnauthorizedUsers() throws Exception {
        Comment answer = comment(question, reader, 0L, "PUBLISHED", "answer");
        Comment child = comment(question, reader, answer.getId(), "PUBLISHED", "child");
        Article other = article("QUESTION");
        Comment elsewhere = comment(other, reader, 0L, "PUBLISHED", "elsewhere");
        mvc.perform(post("/api/comment/like").contentType(MediaType.APPLICATION_JSON).content("{\"commentId\":" + answer.getId() + "}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(post("/api/comment/like").header("Authorization", token(reader)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"commentId\":" + answer.getId() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mvc.perform(delete("/api/comment/like").header("Authorization", token(reader)).param("commentId", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        for (String invalid : List.of("-1", "not-a-number")) {
            mvc.perform(get("/api/comment/listAll").param("articleId", invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
        mvc.perform(get("/api/comment/listAll")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/comment/listAll").param("articleId", question.getId().toString()).param("sort", "invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        for (Long parent : List.of(child.getId(), elsewhere.getId(), -1L)) {
            mvc.perform(post("/api/comment/publish").header("Authorization", token(reader)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"articleId\":" + question.getId() + ",\"content\":\"reply\",\"parentCommentId\":" + parent + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
        comments.holdForTextReview(answer.getId());
        mvc.perform(post("/api/comment/like").header("Authorization", token(reader)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"commentId\":" + answer.getId() + "}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        mvc.perform(delete("/api/admin/comment/delete").header("Authorization", token(reader)).param("commentId", answer.getId().toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/comment/like").header("Authorization", token(reader)).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void approvalConflictAndUnexpectedFailureMatchActualHttpStatus() throws Exception {
        Comment answer = comment(question, reader, 0L, "PUBLISHED", "answer");
        Comment held = heldReply(answer); TextReviewRecord record = review(held);
        comments.holdForTextReview(answer.getId());
        mvc.perform(put("/api/admin/text-moderation/pending/" + record.getId()).header("Authorization", token(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        answer.setStatus("PUBLISHED"); commentMapper.updateById(answer);
        Notification collision = new Notification(); collision.setReceiverId(author.getId()); collision.setType("LIKE");
        collision.setTitle("collision"); collision.setContent("neutral synthetic collision"); collision.setSourceMessageId("comment-reply:" + held.getId()); notificationMapper.insert(collision);
        mvc.perform(put("/api/admin/text-moderation/pending/" + record.getId()).header("Authorization", token(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500));
        assertEquals("PENDING", review(held).getStatus());
    }

    private User user(String role) {
        User user = new User(); user.setUsername("cm22-" + UUID.randomUUID()); user.setPassword("synthetic-unused");
        user.setRole(role); user.setStatus(1); users.insert(user); return user;
    }
    private Article article(String type) {
        Article a = new Article(); a.setTitle("cm22-" + UUID.randomUUID()); a.setContent("synthetic"); a.setUserId(author.getId());
        a.setCategoryId(1L); a.setStatus("PUBLISHED"); a.setContentType(type); articleMapper.insert(a); return a;
    }
    private Comment comment(Article article, User user, Long parent, String state, String text) {
        Comment c = new Comment(); c.setArticleId(article.getId()); c.setUserId(user.getId()); c.setParentCommentId(parent);
        c.setStatus(state); c.setContent(text); commentMapper.insert(c); return c;
    }
    private Comment heldReply(Comment parent) {
        String phrase = "cm22-held-" + UUID.randomUUID(); rules.create(phrase, admin.getId());
        comments.publish(author.getId(), parent.getArticleId(), phrase, parent.getId()); return newest(parent.getArticleId());
    }
    private Comment newest(Long articleId) {
        return commentMapper.selectOne(new QueryWrapper<Comment>().eq("article_id", articleId).orderByDesc("id").last("LIMIT 1"));
    }
    private TextReviewRecord review(Comment c) {
        return reviewMapper.selectOne(new QueryWrapper<TextReviewRecord>().eq("target_type", "COMMENT").eq("target_id", c.getId()).orderByDesc("id").last("LIMIT 1"));
    }
    private Notification event(Comment c) { return notificationMapper.selectOne(new QueryWrapper<Notification>().eq("source_message_id", "comment-reply:" + c.getId())); }
    private long eventCount(Comment c) { return notificationMapper.selectCount(new QueryWrapper<Notification>().eq("source_message_id", "comment-reply:" + c.getId())); }
    private long likeCount(Long id) { return likes.selectCount(new QueryWrapper<AnswerLike>().eq("comment_id", id)); }
    private List<Long> ids(List<Comment> rows) { return rows.stream().map(Comment::getId).toList(); }
    private String token(User user) { return "Bearer " + jwt.generateToken(user.getId(), user.getUsername()); }
    private void expectStatus(int status, Runnable action) { assertEquals(status, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value()); }
    private void race(int count, Runnable action) throws Exception {
        Runnable[] actions = new Runnable[count]; Arrays.fill(actions, action); raceActions(actions);
    }
    private void raceActions(Runnable... actions) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(actions.length); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable action : actions) futures.add(pool.submit(() -> { start.await(); action.run(); return null; }));
            start.countDown(); for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }
}
