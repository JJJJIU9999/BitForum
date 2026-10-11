package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import java.util.concurrent.*;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bitforum.entity.*;
import com.bitforum.mapper.*;
import com.bitforum.util.JwtUtil;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class QuestionIntegrationTest {
    @Autowired private ArticleService articles;
    @Autowired private CommentService comments;
    @Autowired private TextRuleService rules;
    @Autowired private TextReviewService reviews;
    @Autowired private TextRescanService rescans;
    @Autowired private ArticleMapper articleMapper;
    @Autowired private CommentMapper commentMapper;
    @Autowired private CategoryMapper categories;
    @Autowired private UserMapper users;
    @Autowired private NotificationMapper notifications;
    @Autowired private TextReviewRecordMapper reviewMapper;
    @Autowired private MockMvc mvc;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private RedisService redisService;
    @MockitoBean private JwtUtil jwt;

    @Test
    void legacyDefaultAndQuestionDraftRemainCompatible() {
        Long categoryId = categoryId();
        Article legacy = articles.saveDraftWithCoverId("旧文章", "正文", categoryId, 40001L, null);
        Article question = articles.saveDraftWithCoverId("问题", "描述", categoryId, 40001L, null, "QUESTION");
        assertEquals("ARTICLE", articleMapper.selectById(legacy.getId()).getContentType());
        assertEquals("QUESTION", question.getContentType());
        articles.updateDraftWithCoverId(40001L, question.getId(), "更新问题", "背景", categoryId, null);
        assertEquals("QUESTION", articleMapper.selectById(question.getId()).getContentType());
        assertNull(articles.findReadableById(question.getId(), null));
        assertNotNull(articles.findReadableById(question.getId(), 40001L));
    }

    @Test
    void questionsUseArticleAuditAndTopicSearch() throws Exception {
        String title = "m19-" + UUID.randomUUID();
        articles.publishWithCoverId(title, "问题描述", categoryId(), 40001L, null, "QUESTION");
        Article question = articleMapper.selectOne(new QueryWrapper<Article>().eq("title", title));
        assertEquals("PENDING", question.getStatus());
        assertNull(articles.findPublishedById(question.getId()));
        assertFalse(comments.publish(40002L, question.getId(), "不可回复待审问题"));
        assertTrue(articles.approve(question.getId(), 1L));
        assertEquals(1, articles.searchPublishedArticles(title, question.getCategoryId(), 1, 10, "QUESTION").getTotal());
        assertEquals(0, articles.searchPublishedArticles(title, question.getCategoryId(), 1, 10, "ARTICLE").getTotal());
        mvc.perform(get("/api/article/page").param("contentType", "QUESTION"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].contentType").value("QUESTION"));
        mvc.perform(get("/api/article/search").param("keyword", title).param("categoryId", question.getCategoryId().toString())
                .param("contentType", "QUESTION")).andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void typeValidationRejectsInvalidInputAndQuestionsStillRejectExternalImages() throws Exception {
        User author = author();
        when(jwt.getUserId("author")).thenReturn(author.getId());
        mvc.perform(post("/api/article/publish").header("Authorization", "Bearer author").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"标题\",\"content\":\"正文\",\"categoryId\":1,\"contentType\":\"VIDEO\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/article/page").param("contentType", ""))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/article/publish").header("Authorization", "Bearer author").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"标题\",\"content\":\"正文\",\"categoryId\":1,\"contentType\":\"QUESTION\",\"coverUrl\":\"https://example.com/a.png\"}"))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void acceptanceRequiresAuthorAndPublicTopLevelAnswerOfSamePublishedQuestion() {
        Article question = question();
        Comment answer = answer(question, "PUBLISHED", 0L);
        assertStatus(403, () -> articles.acceptAnswer(40002L, question.getId(), answer.getId()));
        Article other = question();
        assertStatus(400, () -> articles.acceptAnswer(40001L, other.getId(), answer.getId()));
        for (String state : new String[]{"PENDING", "REJECTED"}) {
            Comment hidden = answer(question, state, 0L);
            assertStatus(400, () -> articles.acceptAnswer(40001L, question.getId(), hidden.getId()));
        }
        Comment child = answer(question, "PUBLISHED", answer.getId());
        assertStatus(400, () -> articles.acceptAnswer(40001L, question.getId(), child.getId()));
        assertStatus(400, () -> articles.acceptAnswer(40001L, question.getId(), Long.MAX_VALUE));
        question.setContentType("ARTICLE");
        articleMapper.updateById(question);
        assertStatus(404, () -> articles.acceptAnswer(40001L, question.getId(), answer.getId()));
        question.setContentType("QUESTION");
        question.setStatus("OFFLINE");
        articleMapper.updateById(question);
        assertStatus(404, () -> articles.acceptAnswer(40001L, question.getId(), null));
    }

    @Test
    void authorCanAcceptReplaceAndCancelWithoutChangingAnswers() {
        Article question = question();
        Comment first = answer(question, "PUBLISHED", 0L);
        Comment second = answer(question, "PUBLISHED", 0L);
        articles.acceptAnswer(40001L, question.getId(), first.getId());
        articles.acceptAnswer(40001L, question.getId(), first.getId());
        assertEquals(first.getId(), articles.findPublishedById(question.getId()).getAcceptedAnswerId());
        articles.acceptAnswer(40001L, question.getId(), second.getId());
        assertEquals(second.getId(), articleMapper.selectById(question.getId()).getAcceptedAnswerId());
        articles.acceptAnswer(40001L, question.getId(), null);
        assertNull(articleMapper.selectById(question.getId()).getAcceptedAnswerId());
        assertEquals(2, comments.listByArticleId(question.getId()).size());
    }

    @Test
    void matchingAnswerIsHiddenAndNotNotifiedUntilHumanApproval() {
        Article question = question();
        String phrase = "m19-rule-" + UUID.randomUUID();
        rules.create(phrase, 1L);
        assertTrue(comments.publish(40002L, question.getId(), phrase));
        Comment answer = commentMapper.selectOne(new QueryWrapper<Comment>().eq("article_id", question.getId()));
        assertEquals("PENDING", answer.getStatus());
        assertTrue(comments.listByArticleId(question.getId()).isEmpty());
        assertEquals(0, notifications.selectCount(new QueryWrapper<Notification>().eq("article_id", question.getId())));
        assertStatus(400, () -> articles.acceptAnswer(40001L, question.getId(), answer.getId()));
        TextReviewRecord review = reviewMapper.selectOne(new QueryWrapper<TextReviewRecord>()
                .eq("target_type", "COMMENT").eq("target_id", answer.getId()));
        reviews.decide(review.getId(), 1L, true, null);
        articles.acceptAnswer(40001L, question.getId(), answer.getId());
        assertEquals(1, comments.listByArticleId(question.getId()).size());
        Notification notification = notifications.selectOne(new QueryWrapper<Notification>().eq("article_id", question.getId()));
        assertEquals("收到新回答", notification.getTitle());
    }

    @Test
    void deletionAndRealRescanCancelAcceptanceAndApprovalDoesNotRestoreIt() {
        Article question = question();
        Comment first = answer(question, "PUBLISHED", 0L);
        articles.acceptAnswer(40001L, question.getId(), first.getId());
        assertTrue(comments.deleteByAdmin(first.getId()));
        assertNull(articleMapper.selectById(question.getId()).getAcceptedAnswerId());
        Comment second = answer(question, "PUBLISHED", 0L);
        articles.acceptAnswer(40001L, question.getId(), second.getId());
        rules.create(second.getContent(), 1L);
        long version = rescans.preview(1L).ruleVersion();
        rescans.execute(version, "COMMENT:0", 1L);
        assertEquals("PENDING", commentMapper.selectById(second.getId()).getStatus());
        assertNull(articleMapper.selectById(question.getId()).getAcceptedAnswerId());
        assertTrue(comments.listByArticleId(question.getId()).isEmpty());
        TextReviewRecord review = reviewMapper.selectOne(new QueryWrapper<TextReviewRecord>()
                .eq("target_type", "COMMENT").eq("target_id", second.getId()));
        reviews.decide(review.getId(), 1L, true, null);
        assertNull(articleMapper.selectById(question.getId()).getAcceptedAnswerId());
    }

    @Test
    void staleArticleUpdateCannotRestoreAcceptanceAndPublicReadsHideInvalidReferences() {
        Article question = question();
        Comment answer = answer(question, "PUBLISHED", 0L);
        articles.acceptAnswer(40001L, question.getId(), answer.getId());
        Article stale = articleMapper.selectById(question.getId());
        comments.holdForTextReview(answer.getId());
        stale.setTitle("旧快照更新标题");
        articleMapper.updateById(stale);
        assertNull(articleMapper.selectById(question.getId()).getAcceptedAnswerId());
        // Simulate legacy/manual database inconsistency: every public projection must sanitize it.
        articleMapper.setAcceptedAnswer(question.getId(), answer.getId());
        assertNull(articles.findPublishedById(question.getId()).getAcceptedAnswerId());
        assertNull(articles.pageArticles(1, 100, null, "QUESTION").getRecords().stream()
                .filter(a -> a.getId().equals(question.getId())).findFirst().orElseThrow().getAcceptedAnswerId());
    }

    @Test
    void acceptAndCancelHttpRoutesRequireLoginAndValidPayload() throws Exception {
        User owner = author();
        Article question = question();
        question.setUserId(owner.getId());
        articleMapper.updateById(question);
        Comment answer = answer(question, "PUBLISHED", 0L);
        String body = "{\"articleId\":" + question.getId() + ",\"answerId\":" + answer.getId() + "}";
        mvc.perform(put("/api/article/accepted-answer").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/article/accepted-answer").param("articleId", question.getId().toString()))
                .andExpect(status().isUnauthorized());
        when(jwt.getUserId("owner")).thenReturn(owner.getId());
        mvc.perform(put("/api/article/accepted-answer").header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON).content("{\"articleId\":" + question.getId() + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/article/accepted-answer").header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mvc.perform(delete("/api/article/accepted-answer").header("Authorization", "Bearer owner")
                .param("articleId", question.getId().toString())).andExpect(status().isOk());
        assertNull(articleMapper.selectById(question.getId()).getAcceptedAnswerId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentAcceptanceKeepsOneAnswerAndConcurrentRemovalNeverLeavesDanglingAcceptance() throws Exception {
        Article question = question();
        Comment first = answer(question, "PUBLISHED", 0L);
        Comment second = answer(question, "PUBLISHED", 0L);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            race(pool, () -> articles.acceptAnswer(40001L, question.getId(), first.getId()),
                    () -> articles.acceptAnswer(40001L, question.getId(), second.getId()));
            Long accepted = articleMapper.selectById(question.getId()).getAcceptedAnswerId();
            assertTrue(first.getId().equals(accepted) || second.getId().equals(accepted));
            race(pool, () -> acceptIfStillPublic(question, first), () -> comments.deleteByAdmin(first.getId()));
            assertNotEquals(first.getId(), articleMapper.selectById(question.getId()).getAcceptedAnswerId());
            race(pool, () -> acceptIfStillPublic(question, second), () -> comments.holdForTextReview(second.getId()));
            assertNull(articleMapper.selectById(question.getId()).getAcceptedAnswerId());
        } finally {
            pool.shutdownNow();
            commentMapper.delete(new QueryWrapper<Comment>().eq("article_id", question.getId()));
            articleMapper.deleteById(question.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentReviewAndDeletionUseConsistentParentFirstLockOrder() throws Exception {
        Article question = question();
        String phrase = "m19-race-" + UUID.randomUUID();
        rules.create(phrase, 1L);
        comments.publish(40002L, question.getId(), phrase);
        Comment answer = commentMapper.selectOne(new QueryWrapper<Comment>().eq("article_id", question.getId()));
        TextReviewRecord review = reviewMapper.selectOne(new QueryWrapper<TextReviewRecord>()
                .eq("target_type", "COMMENT").eq("target_id", answer.getId()));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            race(pool, () -> {
                try { reviews.decide(review.getId(), 1L, true, null); }
                catch (ResponseStatusException expected) { assertEquals(409, expected.getStatusCode().value()); /* 删除先完成时待办已关闭。 */ }
            }, () -> comments.deleteByAdmin(answer.getId()));
            assertNull(commentMapper.selectById(answer.getId()));
            assertNotEquals("PENDING", reviewMapper.selectById(review.getId()).getStatus());
        } finally {
            pool.shutdownNow();
            notifications.delete(new QueryWrapper<Notification>().eq("article_id", question.getId()));
            reviewMapper.deleteById(review.getId());
            commentMapper.deleteById(answer.getId());
            articleMapper.deleteById(question.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentAnswerAndQuestionDeletionCannotCreateOrphanAnswer() throws Exception {
        Article question = question();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            race(pool, () -> comments.publish(40002L, question.getId(), "普通回答"),
                    () -> articles.deleteByAdmin(question.getId()));
            assertNull(articleMapper.selectById(question.getId()));
            assertEquals(0, commentMapper.selectCount(new QueryWrapper<Comment>().eq("article_id", question.getId())));
        } finally {
            pool.shutdownNow();
            notifications.delete(new QueryWrapper<Notification>().eq("article_id", question.getId()));
            commentMapper.delete(new QueryWrapper<Comment>().eq("article_id", question.getId()));
            articleMapper.deleteById(question.getId());
        }
    }

    private void acceptIfStillPublic(Article question, Comment answer) {
        try {
            articles.acceptAnswer(40001L, question.getId(), answer.getId());
        } catch (ResponseStatusException e) {
            assertEquals(400, e.getStatusCode().value());
        }
    }

    private void race(ExecutorService pool, Runnable left, Runnable right) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Future<?> a = pool.submit(() -> { await(start); left.run(); });
        Future<?> b = pool.submit(() -> { await(start); right.run(); });
        start.countDown();
        a.get(10, TimeUnit.SECONDS);
        b.get(10, TimeUnit.SECONDS);
    }

    private void await(CountDownLatch start) {
        try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }

    private void assertStatus(int expected, Runnable operation) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, operation::run).getStatusCode().value());
    }

    private Long categoryId() { return categories.selectList(null).get(0).getId(); }

    private Article question() {
        Article question = new Article();
        question.setTitle("m19-" + UUID.randomUUID());
        question.setContent("测试问题描述");
        question.setCategoryId(categoryId());
        question.setUserId(40001L);
        question.setStatus("PUBLISHED");
        question.setContentType("QUESTION");
        articleMapper.insert(question);
        return question;
    }

    private Comment answer(Article question, String status, Long parentId) {
        Comment answer = new Comment();
        answer.setArticleId(question.getId());
        answer.setUserId(40002L);
        answer.setContent("m19-answer-" + UUID.randomUUID());
        answer.setStatus(status);
        answer.setParentCommentId(parentId);
        commentMapper.insert(answer);
        return answer;
    }

    private User author() {
        User user = new User();
        user.setUsername("m19-user-" + UUID.randomUUID());
        user.setPassword("test-only");
        user.setRole("USER");
        user.setStatus(1);
        users.insert(user);
        return user;
    }
}
