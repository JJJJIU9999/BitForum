package com.bitforum.ai.recommend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.ZSetOperations;

import com.bitforum.ai.recommend.RecommendRecallService.RecallSource;
import com.bitforum.ai.recommend.RecommendRecallService.RecalledArticle;
import com.bitforum.ai.recommend.RecommendRecallService.RecallRequest;
import com.bitforum.entity.Article;
import com.bitforum.entity.UserFollow;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.UserFollowMapper;
import com.bitforum.service.ArticleService;
import com.bitforum.service.RedisService;

/**
 * 三路召回的单元测试（M17）。
 *
 * <p>重点覆盖三类**容易出错且线上很难发现**的情况：
 * <ol>
 *   <li>来源文章被推荐给它自己（T11 实测：自己的相似度恒为最高，不排除必然出现）；</li>
 *   <li>已下架文章仍被召回（热榜 ZSet 与向量索引里都可能残留）；</li>
 *   <li>某一路挂掉把整个推荐拖垮（推荐是增强能力，缺一路应只是候选变少）。</li>
 * </ol>
 */
class RecommendRecallServiceTest {

    private VectorStore vectorStore;
    private RedisService redisService;
    private ArticleService articleService;
    private ArticleMapper articleMapper;
    private UserFollowMapper userFollowMapper;
    private RecommendRecallService service;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        redisService = mock(RedisService.class);
        articleService = mock(ArticleService.class);
        articleMapper = mock(ArticleMapper.class);
        userFollowMapper = mock(UserFollowMapper.class);
        service = new RecommendRecallService(vectorStore, redisService, articleService,
                articleMapper, userFollowMapper, "为这个句子生成表示以用于检索相关文章：");
    }

    /** 同一篇文章的多个片段只保留最高分；来源文章自身永不出现；非已发布文章被状态校验丢弃。 */
    @Test
    void shouldDeduplicateChunksExcludeSourceAndDropUnpublished() {
        when(articleService.findPublishedById(87L)).thenReturn(published(87L, "Redis 热点数据同步方案", "浏览量与点赞先写 Redis"));
        // document(...) 内部会创建 mock，必须先构造好再交给 thenReturn，
        // 否则会嵌套在 when(...) 参数里触发 Mockito 的 UnfinishedStubbing
        List<Document> documents = List.of(
                document(85L, 0.7000),
                document(85L, 0.7500),
                document(87L, 0.8942),
                document(90L, 0.8000));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(documents);
        // 90 已下架：article 表里查不到它的 PUBLISHED 记录
        when(articleMapper.selectList(any())).thenReturn(List.of(
                published(85L, "Spring Boot 论坛项目实践", "从接口设计到部署"),
                published(87L, "Redis 热点数据同步方案", "浏览量与点赞先写 Redis")));
        when(redisService.getHotList(anyInt())).thenReturn(Set.of());

        List<RecalledArticle> vectorHits = hits(service.recall(RecallRequest.of(13L, 87L, 10)),
                RecallSource.VECTOR);

        assertEquals(List.of(85L), vectorHits.stream().map(RecalledArticle::articleId).toList());
        assertEquals(0.75, vectorHits.get(0).rawScore(), 1e-9, "同文章多片段应取最高相似度");
    }

    /** 未登录访客没有关注关系：不查库，关注路返回空（未登录推荐退化为两路）。 */
    @Test
    void shouldSkipFollowRecallForAnonymousVisitor() {
        when(articleService.findPublishedById(87L)).thenReturn(published(87L, "Redis 热点数据同步方案", "正文"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(redisService.getHotList(anyInt())).thenReturn(Set.of());

        List<RecalledArticle> recalled = service.recall(RecallRequest.of(null, 87L, 5));

        assertTrue(recalled.isEmpty());
        verify(userFollowMapper, never()).selectList(any());
    }

    /** 热榜里残留的已下架文章必须被过滤掉。 */
    @Test
    void shouldDropUnpublishedArticlesFromHotRecall() {
        when(redisService.getHotList(anyInt())).thenReturn(new LinkedHashSet<>(List.of(
                tuple("87", 3.0d),
                tuple("90", 1.0d))));
        when(articleMapper.selectList(any())).thenReturn(List.of(
                published(87L, "Redis 热点数据同步方案", "正文")));

        List<RecalledArticle> hotHits = hits(service.recall(RecallRequest.of(null, null, 5)),
                RecallSource.HOT);

        assertEquals(List.of(87L), hotHits.stream().map(RecalledArticle::articleId).toList());
    }

    /** 关注路：取关注作者的文章。 */
    @Test
    void shouldRecallArticlesFromFollowedAuthors() {
        UserFollow follow = new UserFollow();
        follow.setFollowerId(13L);
        follow.setFollowingId(8L);
        when(userFollowMapper.selectList(any())).thenReturn(List.of(follow));
        when(articleMapper.selectList(any())).thenReturn(List.of(
                published(85L, "Spring Boot 论坛项目实践", "正文")));
        when(redisService.getHotList(anyInt())).thenReturn(Set.of());

        List<RecalledArticle> followHits = hits(service.recall(RecallRequest.of(13L, null, 5)),
                RecallSource.FOLLOW);

        assertEquals(List.of(85L), followHits.stream().map(RecalledArticle::articleId).toList());
    }

    /** 向量库挂掉不应拖垮整次推荐：热度那一路仍要正常返回。 */
    @Test
    void shouldKeepOtherChannelsWhenVectorRecallFails() {
        when(articleService.findPublishedById(85L)).thenReturn(published(85L, "Spring Boot 论坛项目实践", "正文"));
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("Redis 连接中断"));
        when(redisService.getHotList(anyInt())).thenReturn(new LinkedHashSet<>(List.of(tuple("87", 3.0d))));
        when(articleMapper.selectList(any())).thenReturn(List.of(
                published(87L, "Redis 热点数据同步方案", "正文")));

        List<RecalledArticle> recalled = service.recall(RecallRequest.of(null, 85L, 5));

        assertEquals(List.of(87L), hits(recalled, RecallSource.HOT).stream()
                .map(RecalledArticle::articleId).toList());
        assertTrue(hits(recalled, RecallSource.VECTOR).isEmpty());
    }

    /** 排除集里给的文章不应再被召回（产品策略由调用方决定，召回层只负责执行）。 */
    @Test
    void shouldHonourCallerExclusions() {
        when(redisService.getHotList(anyInt())).thenReturn(new LinkedHashSet<>(List.of(
                tuple("87", 3.0d),
                tuple("88", 2.0d))));
        when(articleMapper.selectList(any())).thenReturn(List.of(
                published(87L, "Redis 热点数据同步方案", "正文"),
                published(88L, "社区使用指南", "正文")));

        List<RecalledArticle> hotHits = hits(
                service.recall(RecallRequest.of(null, null, 5, Set.of(87L))), RecallSource.HOT);

        assertEquals(List.of(88L), hotHits.stream().map(RecalledArticle::articleId).toList());
    }

    /** 指定通道时不应碰其它通道（评测的纯热榜基线依赖这个行为）。 */
    @Test
    void shouldRecallOnlyRequestedChannel() {
        when(redisService.getHotList(anyInt())).thenReturn(new LinkedHashSet<>(List.of(tuple("87", 3.0d))));
        when(articleMapper.selectList(any())).thenReturn(List.of(
                published(87L, "Redis 热点数据同步方案", "正文")));

        // 纯热榜基线没有"来源文章"，因此第三个参数传 null；
        // 若传了来源文章 id，它会被召回层强制排除（这正是我们想要的行为）
        List<RecalledArticle> recalled = service.recall(
                RecallRequest.only(RecallSource.HOT, 13L, null, 5, Set.of()));

        assertEquals(List.of(87L), recalled.stream().map(RecalledArticle::articleId).toList());
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
        verify(userFollowMapper, never()).selectList(any());
    }

    private List<RecalledArticle> hits(List<RecalledArticle> recalled, RecallSource source) {
        return recalled.stream().filter(item -> item.source() == source).toList();
    }

    private Article published(Long id, String title, String content) {
        Article article = new Article();
        article.setId(id);
        article.setTitle(title);
        article.setContent(content);
        article.setStatus(ArticleService.STATUS_PUBLISHED);
        return article;
    }

    private Document document(Long articleId, double score) {
        Document document = mock(Document.class);
        when(document.getMetadata()).thenReturn(Map.of("articleId", String.valueOf(articleId)));
        when(document.getScore()).thenReturn(score);
        return document;
    }

    private ZSetOperations.TypedTuple<String> tuple(String value, double score) {
        return new DefaultTypedTuple<>(value, score);
    }
}
