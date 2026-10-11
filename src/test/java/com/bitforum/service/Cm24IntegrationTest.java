package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bitforum.dto.*;
import com.bitforum.entity.*;
import com.bitforum.mapper.*;
import com.bitforum.util.JwtUtil;

@SpringBootTest(properties="spring.rabbitmq.listener.simple.auto-startup=false")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="CM24_ISOLATED_TESTS",matches="true")
class Cm24IntegrationTest {
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        String url=System.getenv("SPRING_DATASOURCE_URL");
        if(url==null||!url.startsWith("jdbc:mysql://127.0.0.1:23306/bitforum_cm24_test?"))throw new IllegalStateException("Dedicated C-M24 database required");
        r.add("spring.datasource.url",()->url);
    }
    @Autowired TopicFollowService topics; @Autowired CategoryService categories; @Autowired CategoryMapper categoryMapper;
    @Autowired ArticleService articles; @Autowired ArticleMapper articleMapper; @Autowired CategoryFollowMapper follows;
    @Autowired UserMapper users; @Autowired UserFollowService userFollows; @Autowired MockMvc mvc; @Autowired JwtUtil jwt;
    @Autowired org.apache.ibatis.session.SqlSessionFactory sessions;
    @Autowired PlatformTransactionManager manager; @Autowired DataSource dataSource;
    @MockitoBean RabbitTemplate rabbit; @MockitoBean RedisService redis;
    User viewer,author,other; Category topic;
    @BeforeEach void fixture() {
        viewer=user();author=user();other=user();topic=categories.create("cm24-"+UUID.randomUUID(),"合成话题",1);
    }
    User user(){User u=new User();u.setUsername("cm24-"+UUID.randomUUID());u.setPassword("synthetic-hash");u.setRole("USER");u.setStatus(1);u.setPublicProfileStatus("PUBLISHED");users.insert(u);return u;}
    Article article(User u,Category c,String type,String status,LocalDateTime time){Article a=new Article();a.setTitle("合成"+UUID.randomUUID());a.setContent("公开测试正文");a.setUserId(u.getId());a.setCategoryId(c.getId());a.setContentType(type);a.setStatus(status);a.setCreateTime(time);articleMapper.insert(a);return a;}
    String token(User u){return "Bearer "+jwt.generateToken(u.getId(),u.getUsername());}
    List<Long> ids(FollowFeedPage p){return p.records().stream().map(Article::getId).toList();}
    long count(){return follows.selectCount(new QueryWrapper<CategoryFollow>().eq("user_id",viewer.getId()).eq("category_id",topic.getId()));}
    @Test void idempotentFollowingAndDisabledPrivateList() {
        topics.follow(viewer.getId(),topic.getId());var before=topics.following(viewer.getId(),1,10).getRecords().get(0).followedAt();
        topics.follow(viewer.getId(),topic.getId());assertEquals(1,count());assertEquals(before,topics.following(viewer.getId(),1,10).getRecords().get(0).followedAt());
        categories.disable(topic.getId());assertEquals(0,topics.following(viewer.getId(),1,10).getRecords().get(0).status());
        assertThrows(ResponseStatusException.class,()->topics.follow(viewer.getId(),topic.getId()));
        topics.unfollow(viewer.getId(),topic.getId());topics.unfollow(viewer.getId(),topic.getId());assertEquals(0,count());
        topics.unfollow(viewer.getId(),Long.MAX_VALUE);
    }
    @Test void concurrentDuplicatesUseRealIndependentConnections() throws Exception {
        Set<String> connections=ConcurrentHashMap.newKeySet();var pool=Executors.newFixedThreadPool(6);var barrier=new CyclicBarrier(6);
        try {var start=new CountDownLatch(1);List<Future<?>> jobs=new ArrayList<>();
            for(int i=0;i<6;i++)jobs.add(pool.submit(()->{start.await();new TransactionTemplate(manager).execute(s->{try(var c=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource).createStatement();var r=c.executeQuery("SELECT CONNECTION_ID()")){r.next();connections.add(r.getString(1));barrier.await(5,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}topics.follow(viewer.getId(),topic.getId());return null;});return null;}));
            start.countDown();for(var job:jobs)job.get(15,TimeUnit.SECONDS);assertEquals(6,connections.size());assertEquals(1,count());
            jobs.clear();for(int i=0;i<6;i++)jobs.add(pool.submit(()->topics.unfollow(viewer.getId(),topic.getId())));
            for(var job:jobs)job.get(15,TimeUnit.SECONDS);assertEquals(0,count());
        } finally {pool.shutdownNow();}
    }
    Object ordered(Runnable first,Runnable second)throws Exception {
        var pool=Executors.newSingleThreadExecutor();List<Future<Object>> pending=new ArrayList<>();var began=new CountDownLatch(1);
        try {new TransactionTemplate(manager).execute(s->{categoryMapper.lockById(topic.getId());first.run();
            pending.add(pool.submit(()->{began.countDown();try{second.run();return null;}catch(Throwable e){return e;}}));
            try {assertTrue(began.await(3,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->pending.get(0).get(250,TimeUnit.MILLISECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return null;});
            return pending.get(0).get(10,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
    }
    @Test void followThenDisableAndDisableThenFollowSerialize()throws Exception {
        assertNull(ordered(()->topics.follow(viewer.getId(),topic.getId()),()->categories.disable(topic.getId())));assertEquals(1,count());
        categories.enable(topic.getId());topics.unfollow(viewer.getId(),topic.getId());
        Object result=ordered(()->categories.disable(topic.getId()),()->topics.follow(viewer.getId(),topic.getId()));
        assertInstanceOf(ResponseStatusException.class,result);assertEquals(404,((ResponseStatusException)result).getStatusCode().value());assertEquals(0,count());
    }
    @Test void followThenDeleteAndDeleteThenFollowCascadeWithoutOrphans()throws Exception {
        assertNull(ordered(()->topics.follow(viewer.getId(),topic.getId()),()->assertEquals(CategoryService.CategoryDeleteResult.SUCCESS,categories.delete(topic.getId()))));assertEquals(0,count());
        topic=categories.create("cm24-empty-"+UUID.randomUUID(),"",0);
        Object result=ordered(()->categories.delete(topic.getId()),()->topics.follow(viewer.getId(),topic.getId()));assertInstanceOf(ResponseStatusException.class,result);assertEquals(0,count());
    }
    @Test void oppositeOperationsAndAdminUpdatePreserveCommittedStatus()throws Exception {
        assertNull(ordered(()->topics.follow(viewer.getId(),topic.getId()),()->topics.unfollow(viewer.getId(),topic.getId())));assertEquals(0,count());
        assertNull(ordered(()->topics.unfollow(viewer.getId(),topic.getId()),()->topics.follow(viewer.getId(),topic.getId())));assertEquals(1,count());
        assertNull(ordered(()->categories.disable(topic.getId()),()->categories.update(topic.getId(),topic.getName(),"新说明",3)));
        assertEquals(0,categoryMapper.selectById(topic.getId()).getStatus());
        article(author,topic,"QUESTION","DRAFT",LocalDateTime.now());assertEquals(CategoryService.CategoryDeleteResult.HAS_ARTICLE,categories.delete(topic.getId()));
    }
    @Test void unionVisibilityAndDisabledTopicLinkMetadata() {
        var elsewhere=categories.create("cm24-other-"+UUID.randomUUID(),"",0);var t=LocalDateTime.of(2020,1,1,0,0);
        Article both=article(author,topic,"QUESTION","PUBLISHED",t),topicOnly=article(other,topic,"ARTICLE","PUBLISHED",t),authorOnly=article(author,elsewhere,"ARTICLE","PUBLISHED",t);
        article(other,elsewhere,"ARTICLE","PUBLISHED",t);
        for(String status:List.of("DRAFT","PENDING","REJECTED","OFFLINE")){article(author,topic,"QUESTION",status,t);article(viewer,topic,"ARTICLE",status,t);}
        userFollows.follow(viewer.getId(),author.getId());topics.follow(viewer.getId(),topic.getId());
        var page=articles.followFeed(viewer.getId(),50,null,null);assertEquals(Set.of(both.getId(),topicOnly.getId(),authorOnly.getId()),new HashSet<>(ids(page)));assertEquals(3,page.records().size());assertTrue(page.records().stream().allMatch(Article::isCategoryAvailable));
        assertEquals(List.of(both.getId()),ids(articles.followFeed(viewer.getId(),50,"QUESTION",null)));
        categories.disable(topic.getId());page=articles.followFeed(viewer.getId(),50,null,null);assertEquals(Set.of(both.getId(),authorOnly.getId()),new HashSet<>(ids(page)));
        assertFalse(articles.findReadableById(both.getId(),null).isCategoryAvailable());assertFalse(page.records().stream().filter(a->a.getId().equals(both.getId())).findFirst().orElseThrow().isCategoryAvailable());
        categories.enable(topic.getId());assertEquals(3,articles.followFeed(viewer.getId(),50,null,null).records().size());
    }
    @Test void keysetTiesNewRowsDeletionAndNullDatesRemainUnique() {
        topics.follow(viewer.getId(),topic.getId());var time=LocalDateTime.of(2020,1,1,0,0);List<Article> rows=new ArrayList<>();for(int i=0;i<5;i++)rows.add(article(author,topic,"ARTICLE","PUBLISHED",time));
        var oldest=article(author,topic,"ARTICLE","PUBLISHED",time);articleMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Article>().eq("id",oldest.getId()).set("create_time",null));
        var first=articles.followFeed(viewer.getId(),2,null,null);assertEquals(List.of(rows.get(4).getId(),rows.get(3).getId()),ids(first));
        article(author,topic,"ARTICLE","PUBLISHED",time.plusDays(1));articleMapper.deleteById(rows.get(2).getId());
        var second=articles.followFeed(viewer.getId(),2,null,first.nextCursor());assertEquals(List.of(rows.get(1).getId(),rows.get(0).getId()),ids(second));
        var last=articles.followFeed(viewer.getId(),2,null,second.nextCursor());assertEquals(List.of(oldest.getId()),ids(last));assertFalse(last.hasMore());
        assertThrows(ResponseStatusException.class,()->articles.followFeed(other.getId(),2,null,first.nextCursor()));
    }

    @Test void explainActualMapperSqlAcrossBatchesTypesAndFollowCounts() throws Exception {
        var time=LocalDateTime.of(2020,1,1,0,0);
        for(int i=0;i<120;i++)article(author,topic,i%2==0?"ARTICLE":"QUESTION","PUBLISHED",time.plusSeconds(i));
        List<Map<String,Object>> evidence=new ArrayList<>();
        for(int followCount:List.of(0,1,25)) {
            if(followCount==1)userFollows.follow(viewer.getId(),author.getId());
            if(followCount==25)for(int i=1;i<25;i++)userFollows.follow(viewer.getId(),user().getId());
            for(String type:Arrays.asList(null,"QUESTION")) for(boolean next:List.of(false,true)) {
                Map<String,Object> params=new HashMap<>();params.put("userId",viewer.getId());params.put("contentType",type);
                params.put("beforeTime",next?time.plusSeconds(60):null);params.put("beforeId",next?Long.MAX_VALUE:null);params.put("limit",9L);
                var bound=sessions.getConfiguration().getMappedStatement("com.bitforum.mapper.ArticleMapper.selectFollowFeed").getBoundSql(params);
                List<Map<String,Object>> plan=new ArrayList<>();
                try(var c=dataSource.getConnection();var statement=c.prepareStatement("EXPLAIN "+bound.getSql())) {
                    int index=1;for(var mapping:bound.getParameterMappings())statement.setObject(index++,params.get(mapping.getProperty()));
                    try(var r=statement.executeQuery()) {while(r.next()){Map<String,Object> row=new LinkedHashMap<>();for(int j=1;j<=r.getMetaData().getColumnCount();j++)row.put(r.getMetaData().getColumnLabel(j),r.getObject(j));plan.add(row);}}
                }
                assertFalse(plan.isEmpty());Map<String,Object> item=new LinkedHashMap<>();item.put("followCount",followCount);item.put("sql",bound.getSql());
                Map<String,Object> safeParams=new LinkedHashMap<>(params);safeParams.replaceAll((k,v)->v instanceof LocalDateTime?v.toString():v);item.put("parameters",safeParams);item.put("plan",plan);
                item.put("articleCount",articleMapper.selectCount(null));evidence.add(item);
            }
        }
        String directory=System.getenv("CM24_EVIDENCE_DIR");assertNotNull(directory);
        assertTrue(directory.startsWith("/private/tmp/bitforum-cm24-") || directory.startsWith("/tmp/bitforum-cm24-"));
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of(directory,"explain.json").toFile(),evidence);
        assertEquals(12,evidence.size());
    }
    @Test void httpGuardsErrorsAndNoPrivateContentEvenForOwner()throws Exception {
        mvc.perform(get("/api/user/follow-feed")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(post("/api/category/{id}/follow",topic.getId())).andExpect(status().isUnauthorized());
        viewer.setStatus(0);users.updateById(viewer);mvc.perform(get("/api/user/followed-topics").header("Authorization",token(viewer))).andExpect(status().isForbidden());viewer.setStatus(1);users.updateById(viewer);
        for(String query:List.of("pageSize=51","pageSize=0","contentType=ALL","cursor=bad"))mvc.perform(get("/api/user/follow-feed?"+query).header("Authorization",token(viewer))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/category/0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/user/followed-topics").param("pageNum",Long.toString(Long.MAX_VALUE)).param("pageSize","50").header("Authorization",token(viewer))).andExpect(status().isBadRequest());
        topics.follow(viewer.getId(),topic.getId());article(viewer,topic,"QUESTION","PENDING",LocalDateTime.now());
        mvc.perform(get("/api/user/follow-feed").header("Authorization",token(viewer))).andExpect(status().isOk()).andExpect(jsonPath("$.data.records").isEmpty());
        mvc.perform(get("/api/category/{id}/contents",topic.getId())).andExpect(jsonPath("$.data.total").value(0));
        categories.disable(topic.getId());mvc.perform(get("/api/category/{id}",topic.getId())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        mvc.perform(get("/api/category/{id}/contents",topic.getId())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/category/{id}/follow",topic.getId()).header("Authorization",token(viewer))).andExpect(status().isOk()).andExpect(jsonPath("$.data.followedByCurrentUser").value(false));
    }
}
