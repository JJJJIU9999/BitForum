package com.bitforum.ai.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bitforum.ai.entity.AiKbDocument;
import com.bitforum.ai.mapper.AiKbDocumentMapper;
import com.bitforum.entity.Article;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.service.ArticleService;

/**
 * S1：知识库索引状态过滤的单元测试。
 *
 * <p>背景：索引流程是「先写 Redis 向量 → 再写 MySQL 分块 → 最后标记 INDEXED」。
 * 后半段失败时向量已经存在，`ai_kb_document` 却停在 {@code FAILED}/{@code PENDING}。
 * 旧实现只校验 `article.status == PUBLISHED`，这类半成品会被当成回答依据。
 *
 * <p>本测试不依赖真实向量库与数据库：向量召回、文章状态、文档状态三层都用打桩，
 * 只验证"进入回答上下文之前过滤了什么"，以及"过滤是批量做的、不是逐条 N+1"。
 */
class RagServiceIndexStatusFilterTest {

    private static final int TOP_K = 5;

    private VectorStore vectorStore;
    private ArticleMapper articleMapper;
    private AiKbDocumentMapper documentMapper;
    private RagService ragService;

    @BeforeAll
    static void initMybatisPlusMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Article.class);
        TableInfoHelper.initTableInfo(assistant, AiKbDocument.class);
    }

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        articleMapper = mock(ArticleMapper.class);
        documentMapper = mock(AiKbDocumentMapper.class);
        ragService = new RagService(vectorStore, articleMapper, documentMapper,
                TOP_K, 0.5, 300, "");
    }

    /** 只有 INDEXED 的文档能进入回答上下文；FAILED / PENDING 一律被丢弃。 */
    @Test
    void failedOrPendingDocumentsShouldNotEnterAnswerContext() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                document(901L, 0, "已完成的片段"),
                document(902L, 0, "失败索引的片段"),
                document(903L, 0, "从未完成索引的片段")));
        // 三篇文章本身都仍是已发布 —— 说明拦截确实来自索引状态，而不是文章状态
        when(articleMapper.selectList(any())).thenReturn(List.of(
                article(901L, "已完成索引的文章"),
                article(902L, "索引失败的文章"),
                article(903L, "索引待完成的文章")));
        when(documentMapper.selectList(any())).thenReturn(List.of(
                kbDocument(901L, AiKbDocument.INDEX_STATUS_INDEXED)));

        RagService.RetrievalResult result = ragService.retrieve("玄武岩缓存策略");

        assertEquals(List.of(901L), result.chunks().stream()
                        .map(chunk -> chunk.citation().articleId()).toList(),
                "只有 INDEXED 文档可以进入回答上下文");
        assertEquals(List.of(901L), result.citations().stream()
                .map(Citation::articleId).toList());
    }

    /** 过滤必须是批量的一次 IN 查询，不能按召回条数逐条查库。 */
    @Test
    void indexStatusCheckShouldBeOneBatchQuery() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                document(901L, 0, "片段一"), document(902L, 0, "片段二")));
        when(articleMapper.selectList(any())).thenReturn(List.of(
                article(901L, "文章一"), article(902L, "文章二")));
        when(documentMapper.selectList(any())).thenReturn(List.of(
                kbDocument(901L, AiKbDocument.INDEX_STATUS_INDEXED),
                kbDocument(902L, AiKbDocument.INDEX_STATUS_INDEXED)));

        RagService.RetrievalResult result = ragService.retrieve("缓存");

        assertEquals(2, result.chunks().size());
        verify(documentMapper, times(1)).selectList(any());
    }

    /** 向量库没有任何命中时不应多查一次文档状态表。 */
    @Test
    void emptyRecallShouldNotQueryDocumentStatus() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        assertTrue(ragService.retrieve("缓存穿透").isEmpty());
        verify(documentMapper, times(0)).selectList(any());
    }

    private Document document(Long articleId, int chunkIndex, String text) {
        return new Document("vec-" + articleId + "-" + chunkIndex, text, Map.of(
                "articleId", String.valueOf(articleId),
                "chunkIndex", chunkIndex));
    }

    private Article article(Long id, String title) {
        Article article = new Article();
        article.setId(id);
        article.setTitle(title);
        article.setStatus(ArticleService.STATUS_PUBLISHED);
        return article;
    }

    private AiKbDocument kbDocument(Long articleId, String indexStatus) {
        AiKbDocument document = new AiKbDocument();
        document.setArticleId(articleId);
        document.setIndexStatus(indexStatus);
        return document;
    }
}
