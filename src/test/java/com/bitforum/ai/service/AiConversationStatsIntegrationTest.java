package com.bitforum.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.bitforum.ai.entity.AiConversation;
import com.bitforum.ai.mapper.AiConversationMapper;

/**
 * S5：会话统计原子自增的集成测试（真实 MySQL）。
 *
 * <p>旧的"读出 → Java 相加 → updateById"在并发发送消息时会丢失增量；
 * 现在加法下推到 SQL，由数据库保证原子性。这里既验证顺序调用的累计结果，
 * 也用一个**确定性**的小并发场景验证"不丢增量"：N 条线程各加 1，
 * 结果必须精确等于 N —— 原子 SQL 下这是必然成立、不会随机失败的断言。
 */
@SpringBootTest
class AiConversationStatsIntegrationTest {

    private static final Long USER_ID = 99110L;

    @Autowired
    private AiConversationService conversationService;
    @Autowired
    private AiConversationMapper conversationMapper;

    private Long conversationId;

    @BeforeEach
    void createConversation() {
        AiConversation conversation = new AiConversation();
        conversation.setUserId(USER_ID);
        conversation.setTitle("S5 会话统计测试");
        conversation.setAgentType("QA");
        conversation.setMessageCount(0);
        conversation.setTotalTokens(0);
        conversationMapper.insert(conversation);
        conversationId = conversation.getId();
    }

    @AfterEach
    void removeConversation() {
        if (conversationId != null) {
            conversationMapper.deleteById(conversationId);
        }
    }

    /** 连续多轮对话：增量必须逐步累加，而不是被最后一次覆盖。 */
    @Test
    void statsShouldAccumulateAcrossSuccessiveCalls() {
        conversationService.updateConversationStats(conversationId, 2, 100);
        conversationService.updateConversationStats(conversationId, 2, 100);
        conversationService.updateConversationStats(conversationId, 1, 50);

        AiConversation refreshed = conversationMapper.selectById(conversationId);
        assertEquals(5, refreshed.getMessageCount(), "消息数应累加为 2+2+1");
        assertEquals(250, refreshed.getTotalTokens(), "token 应累加为 100+100+50");
    }

    /**
     * 并发发送消息：N 个线程各加一次，结果必须精确等于 N（原子自增不会丢增量）。
     *
     * <p>用 {@link CountDownLatch} 让线程尽量同时开始；断言是精确值，不存在随机性。
     */
    @Test
    void concurrentIncrementsShouldNotLoseUpdates() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    conversationService.updateConversationStats(conversationId, 1, 10);
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        AiConversation refreshed = conversationMapper.selectById(conversationId);
        assertEquals(threads, refreshed.getMessageCount(), "并发自增不应丢失任何一次增量");
        assertEquals(threads * 10, refreshed.getTotalTokens());
    }

    /** 会话不存在时影响 0 行，不抛异常（与旧的"查不到就返回"语义一致）。 */
    @Test
    void missingConversationShouldBeANoOp() {
        conversationService.updateConversationStats(999_999_999L, 3, 30);

        assertEquals(0, conversationMapper.selectById(conversationId).getMessageCount());
    }
}
