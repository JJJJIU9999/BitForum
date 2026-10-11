package com.bitforum.service;

import java.time.Duration;

import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

@Service
public class RedisService {
    private static final String PROCESSED_MESSAGE_KEY_PREFIX = "mq:processed:article_publish:";
    private static final Duration PROCESSED_MESSAGE_TTL = Duration.ofDays(7);
    
    @Autowired
    private StringRedisTemplate redisTemplate;

    //文章浏览量+1，返回最新浏览量
    public Long increaseViews(Long articleId) {
        String key = "article:" + articleId + ":views";
        return redisTemplate.opsForValue().increment(key);
    }

    //查当前浏览量
    public Long getViews(Long articleId) {
        Long value = getViewsIfPresent(articleId);
        return value == null ? 0L : value;
    }

    public Long getViewsIfPresent(Long articleId) {
        String value = redisTemplate.opsForValue().get("article:" + articleId + ":views");
        return value == null ? null : Long.parseLong(value);
    }

    //文章点赞，返回 true 表示这次是第一次点赞；返回 false 表示用户已经点过赞
    public boolean like(Long articleId, Long userId) {
        Long addCount = redisTemplate.opsForSet().add("article:" + articleId + ":likes", userId.toString());
        return addCount != null && addCount == 1;
    }

    //取消文章点赞
    public void unlike(Long articleId, Long userId) {
        redisTemplate.opsForSet().remove("article:" + articleId + ":likes", userId.toString());
    }

    //查询用户是否已点赞该文章
    public boolean hasLiked(Long articleId, Long userId) {
        Boolean b = redisTemplate.opsForSet().isMember("article:" + articleId + ":likes", userId.toString());
        return b != null && b;
    }

    //获取点赞数量
    public Long getLikeCount(Long articleId) {
        Long count = getLikeCountIfPresent(articleId);
        return count == null ? 0L : count;
    }

    public Long getLikeCountIfPresent(Long articleId) {
        Boolean exists = redisTemplate.hasKey("article:" + articleId + ":likes");
        if (!Boolean.TRUE.equals(exists)) {
            return null;
        }
        Long size = redisTemplate.opsForSet().size("article:" + articleId + ":likes");
        return size == null ? 0L : size;
    }

    //增加热度，浏览一次增加1热度
    // 通用热度加分方法：浏览传 1，点赞传 3，后续评论/收藏也能复用
    public void increaseHot(Long articleId,double score) {
        redisTemplate.opsForZSet().incrementScore("article:hot", articleId.toString(), score);
    }

    //获取文章热度集合
    public Set<ZSetOperations.TypedTuple<String>> getHotList(int topN) {
        return redisTemplate.opsForZSet().reverseRangeWithScores("article:hot", 0, topN - 1);
    }

    public void deleteArticleData(Long articleId) {
        // 删除文章后同步清理 Redis 中这篇文章的浏览量、点赞集合和热榜成员
        redisTemplate.delete("article:" + articleId + ":views");
        redisTemplate.delete("article:" + articleId + ":likes");
        redisTemplate.opsForZSet().remove("article:hot", articleId.toString());
    }

    public boolean isMessageProcessed(String messageId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(processedMessageKey(messageId)));
    }

    public void markMessageProcessed(String messageId) {
        // 独立 key 让每条消息都有自己的生命周期；7 天覆盖常规排障/重放窗口，避免永久 Set 无限增长。
        redisTemplate.opsForValue().set(processedMessageKey(messageId), "1", PROCESSED_MESSAGE_TTL);
    }

    private String processedMessageKey(String messageId) {
        return PROCESSED_MESSAGE_KEY_PREFIX + messageId;
    }
}
