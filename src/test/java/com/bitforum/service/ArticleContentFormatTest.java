package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.bitforum.entity.Article;
import com.bitforum.entity.Category;
import com.bitforum.exception.BusinessException;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.ArticleFavoriteMapper;
import com.bitforum.mapper.CategoryMapper;
import com.bitforum.message.AfterCommitExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/** No Spring context, database, model, Redis or RabbitMQ connections. */
@ExtendWith(MockitoExtension.class)
class ArticleContentFormatTest {
    @Mock ArticleMapper articleMapper;
    @Mock CategoryService categoryService;
    @Mock CategoryMapper categoryMapper;
    @Mock ArticleFavoriteMapper articleFavoriteMapper;
    @Mock ImageResourceService imageResourceService;
    @Mock TextReviewService textReviewService;
    @Mock AfterCommitExecutor afterCommitExecutor;
    @InjectMocks ArticleService service;
    private static final String RAW = "# 标题\n\n```java\nString x = \"<b>\";\n```";

    private Article owned(String status, String format) {
        Article a = new Article(); a.setId(1L); a.setUserId(2L); a.setCategoryId(3L);
        a.setContent("旧正文"); a.setContentType("QUESTION"); a.setStatus(status); a.setContentFormat(format);
        when(articleMapper.selectById(1L)).thenReturn(a); return a;
    }
    private void category() { when(categoryService.findEnabledById(3L)).thenReturn(new Category()); }

    @ParameterizedTest @ValueSource(strings = {"ARTICLE", "QUESTION"})
    void createsRawMarkdownDraft(String type) {
        category();
        Article a = service.saveDraftWithCoverId("题", RAW, 3L, 2L, null, type, "MARKDOWN");
        assertEquals(RAW, a.getContent()); assertEquals("MARKDOWN", a.getContentFormat());
        assertEquals(type, a.getContentType()); assertEquals("DRAFT", a.getStatus());
        verify(articleMapper).insert(a);
    }
    @Test void oldClientCreationDefaultsToPlainText() {
        category();
        assertEquals("PLAIN_TEXT", service.saveDraftWithCoverId("题", RAW, 3L, 2L, null).getContentFormat());
    }
    @Test void directSubmissionPersistsFormatAndKeepsReview() {
        category(); service.publishWithCoverId("题", RAW, 3L, 2L, null, "ARTICLE", "MARKDOWN");
        verify(articleMapper).insert(argThat((Article a) -> RAW.equals(a.getContent()) && "MARKDOWN".equals(a.getContentFormat()) && "PENDING".equals(a.getStatus())));
        verify(textReviewService).recordIfMatched(eq(TextReviewService.ARTICLE), isNull(), eq("题\n" + RAW), eq("NEW"), isNull(), isNull());
        verify(afterCommitExecutor).run(any());
    }
    @ParameterizedTest @ValueSource(strings = {"DRAFT", "REJECTED"})
    void oldClientUpdateKeepsMarkdown(String status) {
        Article a = owned(status,"MARKDOWN"); service.updateWithCoverId(2L,1L,"新题",RAW,null);
        assertEquals("MARKDOWN",a.getContentFormat()); assertEquals(RAW,a.getContent());
        assertEquals(status,a.getStatus()); assertEquals("QUESTION",a.getContentType());
        verify(articleMapper).updateById(a);
    }
    @Test void explicitSwitchAndDraftEndpointPersistFormat() {
        Article a = owned("REJECTED","PLAIN_TEXT"); category();
        service.updateDraftWithCoverId(2L,1L,"新题",RAW,3L,null,"MARKDOWN");
        assertEquals("MARKDOWN",a.getContentFormat()); assertEquals(RAW,a.getContent()); assertEquals("DRAFT",a.getStatus());
        service.updateDraftWithCoverId(2L,1L,"新题",RAW,3L,null);
        assertEquals("MARKDOWN",a.getContentFormat());
    }
    @Test void legacyNullFormatBecomesPlainWithoutChangingRawText() {
        Article a = owned("DRAFT",null); service.updateWithCoverId(2L,1L,"新题",RAW,null);
        assertEquals("PLAIN_TEXT",a.getContentFormat()); assertEquals(RAW,a.getContent());
    }
    @Test void cannotChangeAnotherAuthorsFormat() {
        owned("DRAFT","PLAIN_TEXT");
        assertThrows(BusinessException.class,()->service.updateWithCoverId(99L,1L,"题",RAW,null,"MARKDOWN"));
        verify(articleMapper,never()).updateById(any(Article.class));
    }
    @ParameterizedTest @ValueSource(strings = {"PENDING","PUBLISHED","OFFLINE"})
    void formatCannotBypassStatus(String status) {
        owned(status,"PLAIN_TEXT");
        assertThrows(BusinessException.class,()->service.updateWithCoverId(2L,1L,"题",RAW,null,"MARKDOWN"));
        verify(articleMapper,never()).updateById(any(Article.class));
    }
    @ParameterizedTest @ValueSource(strings = {"HTML","","markdown"})
    void invalidFormatFailsBeforeWrite(String format) {
        Article a=owned("DRAFT","PLAIN_TEXT");
        assertThrows(ResponseStatusException.class,()->service.updateWithCoverId(2L,1L,"题",RAW,null,format));
        assertEquals("旧正文",a.getContent()); assertEquals("PLAIN_TEXT",a.getContentFormat());
        verify(articleMapper,never()).updateById(any(Article.class));
    }
}
