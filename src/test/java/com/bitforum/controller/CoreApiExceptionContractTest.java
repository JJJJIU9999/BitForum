package com.bitforum.controller;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.lang.reflect.Field;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import com.bitforum.entity.Article;
import com.bitforum.exception.BusinessException;
import com.bitforum.exception.GlobalExceptionHandler;
import com.bitforum.exception.ResultHttpStatusAdvice;

/** Real MVC/advice pipeline; no application context, DB, MQ, Redis or AI. */
class CoreApiExceptionContractTest {
    record Endpoint(Class<?> controller, String method, String path, String body) {}
    static final String ARTICLE = "{\"articleId\":1,\"title\":\"合成标题\",\"content\":\"合成正文\",\"categoryId\":1}";
    static final String AUDIT = "{\"articleId\":1,\"reason\":\"合成原因\"}";
    static final String REPORT = "{\"reportId\":1,\"handleResult\":\"合成处理\"}";
    static Endpoint e(Class<?> c, String m, String p, String b) { return new Endpoint(c,m,p,b); }
    static Stream<Endpoint> endpoints() {
        return Stream.of(
            e(ArticleController.class,"POST","/api/article/publish",ARTICLE),
            e(ArticleController.class,"POST","/api/article/draft",ARTICLE),
            e(ArticleController.class,"PUT","/api/article/draft",ARTICLE),
            e(ArticleController.class,"POST","/api/article/submit",null),
            e(ArticleController.class,"DELETE","/api/article/favorite",null),
            e(ArticleController.class,"PUT","/api/article/update",ARTICLE),
            e(ArticleController.class,"DELETE","/api/article/delete",null),
            e(FileUploadController.class,"UPLOAD","/api/upload/avatar",null),
            e(FileUploadController.class,"UPLOAD","/api/upload/article-cover",null),
            e(UserProfileController.class,"GET","/api/user/profile",null),
            e(UserProfileController.class,"PUT","/api/user/profile","{\"nickname\":\"合成昵称\"}"),
            e(UserProfileController.class,"GET","/api/users/2/profile",null),
            e(AdminArticleController.class,"GET","/api/admin/article/audit/page",null),
            e(AdminArticleController.class,"PUT","/api/admin/article/audit/approve",null),
            e(AdminArticleController.class,"PUT","/api/admin/article/audit/reject",AUDIT),
            e(AdminArticleController.class,"PUT","/api/admin/article/offline",AUDIT),
            e(AdminReportController.class,"GET","/api/admin/reports",null),
            e(AdminReportController.class,"PUT","/api/admin/reports/resolve",REPORT),
            e(AdminReportController.class,"PUT","/api/admin/reports/reject",REPORT),
            e(AdminCategoryController.class,"POST","/api/admin/category/create","{\"name\":\"合成板块\",\"description\":\"合成\",\"sortOrder\":1}"),
            e(AdminImageResourceController.class,"PUT","/api/admin/images/1/approve",null),
            e(AdminImageResourceController.class,"PUT","/api/admin/images/1/reject","{\"reason\":\"合成原因\"}"),
            e(UserArticleController.class,"GET","/api/user/articles",null),
            e(UserReportController.class,"POST","/api/user/reports/article","{\"articleId\":1,\"reason\":\"合成举报\"}"),
            e(UserReportController.class,"POST","/api/user/reports/comment","{\"commentId\":1,\"reason\":\"合成举报\"}"),
            e(UserFollowController.class,"POST","/api/users/2/follow",null),
            e(UserFollowController.class,"DELETE","/api/users/2/follow",null),
            e(UserFollowController.class,"GET","/api/users/2/followers",null),
            e(UserFollowController.class,"GET","/api/users/2/following",null),
            e(UserFollowController.class,"GET","/api/users/2/follow-stats",null),
            e(UserNotificationController.class,"PUT","/api/user/notifications/read",null)
        );
    }

    MockMvc mvc(Class<?> type, RuntimeException failure) throws Exception {
        Object controller = type.getDeclaredConstructor().newInstance();
        for (Field field : type.getDeclaredFields()) {
            if (!field.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)) continue;
            field.setAccessible(true);
            field.set(controller, mock(field.getType(), invocation -> {
                if (failure != null) throw failure;
                if (invocation.getMethod().getName().equals("findById") && invocation.getMethod().getReturnType() == Article.class) {
                    Article article = new Article(); article.setId(1L); article.setUserId(1L); return article;
                }
                return Answers.RETURNS_DEFAULTS.answer(invocation);
            }));
        }
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(), new ResultHttpStatusAdvice()).build();
    }

    MockHttpServletRequestBuilder request(Endpoint e) {
        MockHttpServletRequestBuilder r = e.method.equals("UPLOAD")
            ? MockMvcRequestBuilders.multipart(e.path).file(new MockMultipartFile("file","synthetic.png","image/png",new byte[]{1}))
            : MockMvcRequestBuilders.request(HttpMethod.valueOf(e.method),e.path);
        r.requestAttr("userId",1L).param("articleId","1").param("imageId","1")
            .param("notificationId","1").param("reason","合成原因");
        if (e.body != null) r.contentType(MediaType.APPLICATION_JSON).content(e.body);
        return r;
    }

    @ParameterizedTest @MethodSource("endpoints")
    void businessRefusalKeepsSafeMessageAndStatus(Endpoint e) throws Exception {
        mvc(e.controller,new BusinessException("合成业务拒绝")).perform(request(e))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("合成业务拒绝"));
    }

    @ParameterizedTest @MethodSource("endpoints")
    void internalFailureNeverLeaks(Endpoint e) throws Exception {
        mvc(e.controller,new IllegalArgumentException("sensitive SQL /private/connection")).perform(request(e))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.message").value("服务器内部错误，请稍后尝试"));
    }

    @ParameterizedTest @MethodSource("endpoints")
    void explicitStatusExceptionIsNotCollapsedTo400(Endpoint e) throws Exception {
        mvc(e.controller,new ResponseStatusException(HttpStatus.CONFLICT,"合成状态冲突")).perform(request(e))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value("合成状态冲突"));
    }

    @ParameterizedTest @MethodSource("endpoints")
    void successfulEnvelopeRemainsCompatible(Endpoint e) throws Exception {
        mvc(e.controller,null).perform(request(e))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    }

    @Test void statusExceptionWithInternalReasonIsSanitized() throws Exception {
        Endpoint e = e(UserProfileController.class,"GET","/api/user/profile",null);
        mvc(e.controller,new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"sensitive SQL path")).perform(request(e))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503))
            .andExpect(jsonPath("$.message").value("服务器内部错误，请稍后尝试"));
    }

    @Test void adminTextInternalStateFailureMustNotBecomeConflict() throws Exception {
        mvc(AdminTextModerationController.class,new IllegalStateException("sensitive SHA failure"))
            .perform(MockMvcRequestBuilders.get("/api/admin/text-moderation/rules"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500));
    }

    static Stream<Endpoint> legacyCoverEndpoints() {
        return endpoints().filter(e -> e.controller == ArticleController.class && e.body != null);
    }

    @ParameterizedTest @MethodSource("legacyCoverEndpoints")
    void controllerBusinessValidationKeeps400(Endpoint e) throws Exception {
        String body = e.body.substring(0,e.body.length()-1) + ",\"coverUrl\":\"https://example.invalid/synthetic.png\"}";
        mvc(e.controller,null).perform(request(new Endpoint(e.controller,e.method,e.path,body)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("封面只允许选择站内审核资源，请使用 coverImageId"));
    }
}
