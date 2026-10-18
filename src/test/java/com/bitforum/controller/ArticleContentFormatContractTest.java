package com.bitforum.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bitforum.entity.Article;
import com.bitforum.exception.GlobalExceptionHandler;
import com.bitforum.exception.ResultHttpStatusAdvice;
import com.bitforum.service.ArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;

/** MVC/validation/JSON only, no application context or external services. */
class ArticleContentFormatContractTest {
    ArticleService service; MockMvc mvc;
    @BeforeEach void setup() {
        service=mock(ArticleService.class); ArticleController c=new ArticleController();
        ReflectionTestUtils.setField(c,"articleService",service);
        mvc=MockMvcBuilders.standaloneSetup(c).setControllerAdvice(new GlobalExceptionHandler(),new ResultHttpStatusAdvice()).build();
    }
    @ParameterizedTest @ValueSource(strings={"POST /api/article/draft","POST /api/article/publish","PUT /api/article/draft","PUT /api/article/update"})
    void rejectsUnknownFormatBeforeService(String endpoint) throws Exception {
        var parts=endpoint.split(" ");
        var request=parts[0].equals("POST")?post(parts[1]):put(parts[1]);
        mvc.perform(request.requestAttr("userId",2L).contentType(MediaType.APPLICATION_JSON)
            .content("{\"articleId\":1,\"title\":\"题\",\"content\":\"正文\",\"categoryId\":3,\"contentFormat\":\"HTML\"}"))
            .andExpect(status().isBadRequest()); verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(strings={"POST /api/article/draft","POST /api/article/publish","PUT /api/article/draft","PUT /api/article/update"})
    void passesFormatThroughAllWritePaths(String endpoint) throws Exception {
        Article article=new Article();article.setUserId(2L);
        if(endpoint.endsWith("/update"))when(service.findById(1L)).thenReturn(article);
        var parts=endpoint.split(" ");var request=parts[0].equals("POST")?post(parts[1]):put(parts[1]);
        mvc.perform(request.requestAttr("userId",2L).contentType(MediaType.APPLICATION_JSON)
            .content("{\"articleId\":1,\"title\":\"题\",\"content\":\"# 原文\",\"categoryId\":3,\"contentFormat\":\"MARKDOWN\"}"))
            .andExpect(status().isOk());
        switch(endpoint) {
            case "POST /api/article/draft" -> verify(service).saveDraftWithCoverId("题","# 原文",3L,2L,null,null,"MARKDOWN");
            case "POST /api/article/publish" -> verify(service).publishWithCoverId("题","# 原文",3L,2L,null,null,"MARKDOWN");
            case "PUT /api/article/draft" -> verify(service).updateDraftWithCoverId(2L,1L,"题","# 原文",3L,null,"MARKDOWN");
            case "PUT /api/article/update" -> verify(service).updateWithCoverId(2L,1L,"题","# 原文",null,"MARKDOWN");
        }
    }
}
