package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

@SpringBootTest
public class RedisServiceTest {
    @Autowired
    private RedisService redisService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private Long articleId = 999999L;
    private Long userId = 888888L;
    private Long lowHotArticleId = 999997L;
    private Long highHotArticleId = 999998L;
    private String messageId;

    @BeforeEach
    void setUp() {
        // 每个测试开始前清理测试 key，避免 Redis 里的旧数据影响本次断言
        stringRedisTemplate.delete("article:" + articleId + ":views");
        stringRedisTemplate.delete("article:" + articleId + ":likes");
        stringRedisTemplate.opsForZSet().remove("article:hot",
                lowHotArticleId.toString(),
                highHotArticleId.toString());
        messageId = "test-message-" + UUID.randomUUID();
        stringRedisTemplate.delete(processedMessageKey());
    }

    @AfterEach
    void tearDown() {
        // 测试结束后再清理一次，避免测试数据留在 Redis 中影响手动调试
        stringRedisTemplate.delete("article:" + articleId + ":views");
        stringRedisTemplate.delete("article:" + articleId + ":likes");
        stringRedisTemplate.opsForZSet().remove("article:hot",
                lowHotArticleId.toString(),
                highHotArticleId.toString());
        stringRedisTemplate.delete(processedMessageKey());
    }

    @Test
    void sameUserLikeSameArticleOnlyCountOnce() {
        // Redis Set 天然去重：同一个 userId 重复 add 到同一个 Set，成员数量仍然是 1
        boolean firstLike = redisService.like(articleId, userId);
        boolean secondLike = redisService.like(articleId, userId);

        assertTrue(firstLike);
        assertFalse(secondLike);
        assertEquals(1L, redisService.getLikeCount(articleId));
        assertTrue(redisService.hasLiked(articleId, userId));
    }

    @Test
    void nullableMetricReadersShouldReturnNullWhenRedisKeyMissing() {
        assertNull(redisService.getViewsIfPresent(articleId));
        assertNull(redisService.getLikeCountIfPresent(articleId));

        redisService.increaseViews(articleId);
        redisService.like(articleId, userId);

        assertEquals(1L, redisService.getViewsIfPresent(articleId));
        assertEquals(1L, redisService.getLikeCountIfPresent(articleId));
    }

    @Test
    void hotListShouldReturnHigherScoreArticleFirst() {
        // 使用足够大的测试分数，避免本地演示数据 article:hot 里已有的热门文章影响断言
        redisService.increaseHot(lowHotArticleId, 1_000_001);
        redisService.increaseHot(highHotArticleId, 1_000_003);

        // Redis 的 ZSet 查询结果用 Set 承接：每个元素都是一条排行记录，里面同时包含 value 和 score
        // 这里不要命名成 hotList，因为它的实际类型不是 List；叫 hotSet 更容易看出它来自 Redis ZSet
        Set<ZSetOperations.TypedTuple<String>> hotSet = redisService.getHotList(2);

        // reverseRangeWithScores 会按分数从高到低返回，所以 iterator().next() 拿到的就是第一名
        ZSetOperations.TypedTuple<String> first = hotSet.iterator().next();

        // value 存的是文章 ID，score 存的是热度分；高热度文章应该排在第一位
        assertEquals(highHotArticleId.toString(), first.getValue());
        assertEquals(1_000_003.0, first.getScore());
    }

    @Test
    void processedMessageShouldBeRecordedWithBoundedTtl() {
        assertFalse(redisService.isMessageProcessed(messageId));

        redisService.markMessageProcessed(messageId);

        assertTrue(redisService.isMessageProcessed(messageId));
        Long ttlDays = stringRedisTemplate.getExpire(processedMessageKey(), TimeUnit.DAYS);
        assertTrue(ttlDays != null && ttlDays >= 6 && ttlDays <= 7);
    }

    private String processedMessageKey() {
        return "mq:processed:article_publish:" + messageId;
    }
}
