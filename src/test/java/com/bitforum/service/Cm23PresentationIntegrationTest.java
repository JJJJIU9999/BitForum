package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bitforum.entity.*;
import com.bitforum.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "CM23_ISOLATED_TESTS", matches = "true")
class Cm23PresentationIntegrationTest {
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry registry) {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (url == null || !url.startsWith("jdbc:mysql://127.0.0.1:23306/bitforum_cm23_test?"))
            throw new IllegalStateException("C-M23 requires its new dedicated Compose database");
        registry.add("spring.datasource.url", () -> url);
    }
    @Autowired UserService users;
    @Autowired ArticleService articles;
    @Autowired CommentService comments;
    @MockitoSpyBean UserMapper userMapper;
    @MockitoSpyBean CommentMapper commentMapper;
    @Autowired ArticleMapper articleMapper;
    @Autowired CategoryMapper categoryMapper;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean RabbitTemplate rabbit;
    @MockitoBean RedisService redis;

    @Test void summariesRespectPublicReviewRulesAndMissingUsers() throws Exception {
        User normal = user("PUBLISHED", "公开昵称", "/api/images/7/content");
        User pending = user("PENDING", "不应公开的候选名", null);
        User disabled = user("PUBLISHED", "禁用但公开", null);
        disabled.setStatus(0); userMapper.updateById(disabled);
        User abnormal = user("PUBLISHED", "  ", "https://untrusted.invalid/avatar");
        var result = users.publicAuthorSummaries(Arrays.asList(normal.getId(), pending.getId(), disabled.getId(), abnormal.getId(), Long.MAX_VALUE, null));
        assertEquals("公开昵称", result.get(normal.getId()).displayName());
        assertEquals("/api/images/7/content", result.get(normal.getId()).avatar());
        assertEquals("待审核用户", result.get(pending.getId()).displayName());
        assertEquals("禁用但公开", result.get(disabled.getId()).displayName());
        assertEquals(abnormal.getUsername(), result.get(abnormal.getId()).displayName());
        assertNull(result.get(abnormal.getId()).avatar());
        assertFalse(result.get(Long.MAX_VALUE).profileAvailable());
        Set<String> keys = new HashSet<>(); json.valueToTree(result.get(normal.getId())).fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("userId", "displayName", "avatar", "profileAvailable"), keys);
    }

    @Test void authorsAreDeduplicatedBatchedAndEmptyInputDoesNotQuery() {
        List<Long> ids = new ArrayList<>();
        for (int i=0;i<205;i++) ids.add(user("PUBLISHED", "作者"+i, null).getId());
        clearInvocations(userMapper);
        List<Long> repeated = new ArrayList<>(ids); repeated.addAll(ids);
        assertEquals(205, users.publicAuthorSummaries(repeated).size());
        verify(userMapper, times(2)).selectList(any(QueryWrapper.class));
        verify(userMapper, never()).selectById(any());
        clearInvocations(userMapper); assertTrue(users.publicAuthorSummaries(List.of()).isEmpty());
        verifyNoInteractions(userMapper);
    }

    @Test void listsCountOnlyPublicRootsAndPreserveOldShape() throws Exception {
        User author=user("PUBLISHED", "提问者", null);
        Article question=article(author.getId(), "QUESTION", "PUBLISHED");
        Article ordinary=article(author.getId(), "ARTICLE", "PUBLISHED");
        Article draft=article(author.getId(), "QUESTION", "DRAFT");
        Comment root=comment(question,author,0L,"PUBLISHED");
        comment(question,author,root.getId(),"PUBLISHED");
        comment(question,author,0L,"PENDING"); comment(question,author,0L,"REJECTED");
        comment(ordinary,author,0L,"PUBLISHED");
        var result=articles.searchPublishedArticles(question.getTitle(),null,1,8,"QUESTION");
        assertEquals(1,result.getTotal()); assertEquals(1L,result.getRecords().get(0).getAnswerCount());
        assertEquals("提问者",result.getRecords().get(0).getAuthor().displayName());
        assertNull(articles.findReadableById(ordinary.getId(),null).getAnswerCount());
        assertNull(articles.findReadableById(draft.getId(),author.getId()).getAnswerCount());
        mvc.perform(get("/api/article/search").param("keyword",question.getTitle()).param("contentType","QUESTION"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].answerCount").value(1))
                .andExpect(jsonPath("$.data.records[0].author.displayName").value("提问者"))
                .andExpect(jsonPath("$.data.records[0].author.password").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].author.pendingAvatarImageId").doesNotExist());
        comments.deleteByAdmin(root.getId()); assertEquals(0L,articles.findReadableById(question.getId(),null).getAnswerCount());
    }

    @Test void countAndAuthorQueriesAreBatchedAcrossTheWholePage() {
        User author=user("PUBLISHED","唯一作者",null);
        String keyword="cm23-page-"+UUID.randomUUID();
        for(int i=0;i<201;i++) {
            Article a=article(author.getId(),"QUESTION","PUBLISHED");
            a.setTitle(keyword+"-"+i);articleMapper.updateById(a);
        }
        clearInvocations(userMapper,commentMapper);
        var page=articles.searchPublishedArticles(keyword,null,1,210,"QUESTION");
        assertEquals(201,page.getRecords().size());
        verify(userMapper,times(1)).selectList(any(QueryWrapper.class));
        verify(userMapper,never()).selectById(any());
        verify(commentMapper,times(2)).selectMaps(any(QueryWrapper.class));
        assertTrue(page.getRecords().stream().allMatch(a->Long.valueOf(0).equals(a.getAnswerCount())));
    }

    @Test void summariesAreAddedAfterBranchVisibilityFiltering() throws Exception {
        User author=user("PENDING","隐藏候选名",null);
        Article a=article(author.getId(),"QUESTION","PUBLISHED");
        Comment root=comment(a,author,0L,"PUBLISHED");
        Comment child=comment(a,author,root.getId(),"PENDING");
        assertEquals("待审核用户",comments.listByArticleId(a.getId()).get(0).getAuthor().displayName());
        assertTrue(comments.listOwnByArticleId(author.getId(),a.getId()).stream().anyMatch(c->c.getId().equals(child.getId())));
        comments.holdForTextReview(root.getId());
        assertTrue(comments.listByArticleId(a.getId()).isEmpty());
        assertFalse(comments.listOwnByArticleId(author.getId(),a.getId()).stream().anyMatch(c->c.getId().equals(child.getId())));
        mvc.perform(get("/api/comment/listAll").param("articleId",a.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test void nonCommunityReadsDoNotAddPresentationQueries() {
        User author=user("PUBLISHED","作者",null);
        Article a=article(author.getId(),"QUESTION","PUBLISHED");
        clearInvocations(userMapper,commentMapper);
        assertNull(articles.findPublishedById(a.getId()).getAuthor());
        articles.pageAdminArticles(1,8,null);
        verify(userMapper,never()).selectList(any(QueryWrapper.class));
        verify(commentMapper,never()).selectMaps(any(QueryWrapper.class));
    }

    private User user(String state,String nickname,String avatar) {
        User u=new User();u.setUsername("cm23-"+UUID.randomUUID());u.setPassword("synthetic-hash");u.setRole("USER");
        u.setStatus(1);u.setPublicProfileStatus(state);u.setNickname(nickname);u.setAvatar(avatar);userMapper.insert(u);return u;
    }
    private Article article(Long owner,String type,String state) {
        Article a=new Article();a.setUserId(owner);a.setContentType(type);a.setStatus(state);a.setTitle("cm23-"+UUID.randomUUID());a.setContent("正文");
        a.setCategoryId(categoryMapper.selectList(null).get(0).getId());articleMapper.insert(a);return a;
    }
    private Comment comment(Article a,User user,Long parent,String state) {
        Comment c=new Comment();c.setArticleId(a.getId());c.setUserId(user.getId());c.setParentCommentId(parent);c.setStatus(state);c.setContent("合成评论");commentMapper.insert(c);return c;
    }
}
