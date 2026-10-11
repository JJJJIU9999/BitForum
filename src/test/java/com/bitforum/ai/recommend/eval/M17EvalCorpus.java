package com.bitforum.ai.recommend.eval;

import java.util.List;

/**
 * M17 智能推荐评测语料（**合成数据**，如实声明：不是真实社区内容）。
 *
 * <p><b>本文件是从数据库反向导出的</b>：内容与 `m17-eval-report.md` 所评测的那批数据
 * （`[M17Eval]` 前缀的 50 篇文章）**逐字一致**，以保证"仓库中的语料"能复现"已评测的数据"。
 * 语料在评测过程中曾被改写成另一个版本，导致文件与数据不一致；这里是据此做的对齐，
 * 实现代码（RecommendService / 召回 / 融合）未做任何改动。
 *
 * <p>构成：8 个技术主题共 50 篇（spring 7、persistence 7、redis 6、mq 6、
 * frontend 6、deploy 6、concurrency 6、api 6），每篇 300~800 字真实技术内容。
 * 主题与篇数由 `m17-eval-protocol.md` §6.2 规定，{@code M17EvalDataSeeder} 会校验。
 */
final class M17EvalCorpus {

    /** 一篇文章的语料：所属主题、标题、正文 */
    record Spec(String theme, String title, String content) {
    }

    private M17EvalCorpus() {
    }

    static final List<Spec> ARTICLES = List.of(
            new Spec("spring", "Spring Bean 生命周期的九个阶段与容器扩展点", """
                    容器启动扫描到 BeanDefinition 之后，先执行 InstantiationAwareBeanPostProcessor 的 postProcessBeforeInstantiation，再调用构造器实例化对象，随后进入 populateBean 完成属性填充与依赖注入。若实现了 Aware 系列接口，会依次收到 BeanNameAware、BeanFactoryAware、ApplicationContextAware 回调，此时才能拿到容器与自身名称。

                    接着执行 BeanPostProcessor 的 postProcessBeforeInitialization，再依次触发 @PostConstruct、InitializingBean 的 afterPropertiesSet、@Bean(initMethod) 三个初始化入口，最后 postProcessAfterInitialization 常被用来生成代理对象。初始化完成后 Bean 才真正可用，并被放入 singletonObjects 一级缓存。

                    销毁阶段在容器关闭时走 @PreDestroy、DisposableBean 的 destroy、destroyMethod。原型作用域的 Bean 容器不负责销毁，必须由调用方自行释放。理清这条顺序的价值在于排查注入为空、AOP 未生效、初始化逻辑早于依赖就绪等典型问题。
                    """),
            new Spec("spring", "构造器注入、字段注入与三级缓存解决循环依赖", """
                    构造器注入把依赖声明为 final，对象创建后不可变，依赖缺失会在启动期直接抛 NoSuchBeanDefinitionException，也便于单元测试直接 new。字段注入靠反射赋值，隐藏依赖关系，脱离容器无法使用，IDE 会提示不推荐。setter 注入适合可选依赖，可配合 @Autowired(required = false) 使用。

                    循环依赖指 A 依赖 B、B 又依赖 A。Spring 用三级缓存化解：singletonObjects 存成品，earlySingletonObjects 存提前暴露的半成品，singletonFactories 存 ObjectFactory。实例化 A 后先把工厂放入三级缓存，填充属性时触发 B 创建，B 取到 A 的早期引用后完成创建，A 再走完初始化。

                    构造器注入造成的循环依赖无法提前暴露引用，会抛 BeanCurrentlyInCreationException。Spring Boot 2.6 起默认禁止循环依赖，需显式设置 spring.main.allow-circular-references=true。@Lazy 生成延迟代理也能打破环，但更推荐按职责拆分子模块，从设计上消除环。
                    """),
            new Spec("spring", "Spring Boot 自动配置的条件装配与加载链路", """
                    @SpringBootApplication 由 @SpringBootConfiguration、@ComponentScan、@EnableAutoConfiguration 三者组成。@EnableAutoConfiguration 通过 AutoConfigurationImportSelector 读取类路径下 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports，得到候选配置类列表，再逐条执行条件过滤。

                    条件注解决定装配开关：@ConditionalOnClass 判断类路径是否存在目标类，@ConditionalOnMissingBean 保证用户自定义的 Bean 优先，@ConditionalOnProperty 读取开关项，@ConditionalOnWebApplication 区分 Web 环境。装配先后顺序由 @AutoConfigureBefore、@AutoConfigureAfter、@AutoConfigureOrder 约束，顺序错乱会导致 Bean 覆盖。

                    排查时加 --debug 启动，ConditionEvaluationReport 会打印 Positive matches 与 Negative matches；Actuator 的 /actuator/conditions 端点也能查看同一份报告。@ConfigurationProperties 把 spring.datasource 这类前缀绑定到 POJO，配合 @EnableConfigurationProperties 或 @ConfigurationPropertiesScan 生效。
                    """),
            new Spec("spring", "Spring AOP 代理选择与切面失效的三类原因", """
                    Spring AOP 默认对实现了接口的目标使用 JDK 动态代理，接口方法之外的能力拿不到；设置 @EnableAspectJAutoProxy(proxyTargetClass = true) 或 spring.aop.proxy-target-class=true 后统一走 CGLIB 子类代理。CGLIB 无法代理 final 类与 final 方法，private 方法也不会被拦截，因为它不具备可重写的签名。

                    切点常用 execution(* com.bitforum.service..*.*(..)) 描述，@within 与 @annotation 更适合按注解匹配。通知顺序为 @Around 前后包裹 @Before 与 @AfterReturning、@AfterThrowing、@After，多个切面之间用 @Order 或 Ordered 接口定序，@Around 必须调用 joinPoint.proceed() 否则目标方法不会执行。

                    失效常见三类原因：同类内部方法自调用不经过代理对象、目标方法不是 public、异常被 catch 后未继续抛出导致 @AfterThrowing 不触发。事务注解与缓存注解同源，都依赖代理对象发起调用，因此排查思路完全一致，先确认调用是否穿过代理。
                    """),
            new Spec("spring", "Spring MVC 参数绑定、数据校验与返回值处理", """
                    DispatcherServlet 把请求交给 RequestMappingHandlerAdapter，再由 HandlerMethodArgumentResolver 逐个解析参数。@RequestParam 处理查询串与表单，@PathVariable 取路径变量，@RequestBody 借助 HttpMessageConverter 与 Jackson 反序列化请求体，@RequestHeader 读取请求头；不带注解的 POJO 由 ServletModelAttributeMethodProcessor 完成数据绑定。

                    @Valid 或 @Validated 触发 JSR-380 校验，失败时抛 MethodArgumentNotValidException，其 BindingResult 可通过 FieldError 的 getField 与 getDefaultMessage 取出字段名与提示。@ControllerAdvice 中的 @ExceptionHandler 是统一转换这些异常的位置，避免每个 Controller 重复写 try-catch。

                    返回值由 HandlerMethodReturnValueHandler 处理，@ResponseBody 走 RequestResponseBodyMethodProcessor 并按 Accept 头做内容协商；返回 String 时若类上有 @RestController 则视为响应体，否则当作视图名交给 ViewResolver。日期格式差异、null 字段是否序列化，都来自 ObjectMapper 的配置而不是框架默认行为。
                    """),
            new Spec("spring", "Spring 事务传播行为的七种类型与失效场景", """
                    @Transactional 的传播行为共七种。REQUIRED 默认加入当前事务；REQUIRES_NEW 挂起外层事务并开启新事务，适合写操作日志或审计记录；NESTED 借助 savepoint 实现子事务，子事务回滚不影响外层；SUPPORTS 有事务就加入、没有就以非事务方式运行；NOT_SUPPORTED 挂起当前事务；MANDATORY 要求必须已存在事务；NEVER 要求必须没有事务。

                    isolation 可指定 READ_COMMITTED 等隔离级别，但 NESTED 只对 DataSourceTransactionManager 这类支持保存点的管理器有效。rollbackFor 默认只对 RuntimeException 与 Error 回滚，受检异常必须显式声明才会回滚；readOnly=true 会把只读提示传给底层连接，供驱动与数据库做优化。

                    失效场景包括方法不是 public、同类自调用不走代理、异常被捕获未抛出、多数据源未指定对应事务管理器、事务上下文未传递到异步线程。排查顺序是先确认调用是否经过代理对象，再确认事务管理器绑定的数据源与当前操作的数据源是否一致。
                    """),
            new Spec("spring", "配置属性绑定、Profile 与外部化配置优先级", """
                    @ConfigurationProperties(prefix = "bitforum.upload") 能把配置绑定到 POJO，支持松散绑定，max-file-size 与 max_file_size 都会映射到 maxFileSize，还可加 @Validated 在启动期做校验。@Value 只适合注入少量单值，无法绑定嵌套对象与集合，也不支持松散绑定与元数据提示。

                    Profile 用 application-dev.yml、application-prod.yml 组织，spring.profiles.active 决定生效文件，@Profile("prod") 控制 Bean 是否注册。外部化配置优先级从高到低大致为命令行参数、Java 系统属性、操作系统环境变量、jar 包外 config 目录、jar 包内 application.yml，排查线上配置被覆盖时按这个顺序倒查。

                    @TestPropertySource 与 @DynamicPropertySource 用于测试覆盖配置。口令等敏感项不应提交进仓库，可交给配置中心下发或用 jasypt 加密。运行期需要动态刷新时用 @RefreshScope，它通过销毁并重建 Bean 让新值生效，因此被刷新的 Bean 不宜持有长连接等重资源。
                    """),
            new Spec("persistence", "MyBatis-Plus 条件构造器、分页插件与逻辑删除", """
                    BaseMapper 提供 insert、selectById、updateById、deleteById 等通用方法，条件拼装靠 QueryWrapper 与 LambdaQueryWrapper，后者用 SFunction 引用字段，例如 wrapper.eq(User::getStatus, 1).like(User::getNickname, "bit")，从而避免硬编码列名。条件是否参与拼接可用带 condition 布尔参数的重载控制。

                    分页必须注册 MybatisPlusInterceptor 并加入 PaginationInnerInterceptor，否则 selectPage 不会真正改写 SQL。该拦截器默认先执行 count 再执行 limit，复杂查询可在 Mapper 上单独指定自定义 count 语句以降低开销。分页结果由 Page 对象承载，包含 current、size、total、records 与 pages。

                    逻辑删除用 @TableLogic 标注字段，删除语句被改写为 UPDATE 并把标记位改为 1，查询自动追加未删除条件。自动填充交给 MetaObjectHandler 处理 createTime 与 updateTime 等字段。乐观锁用 @Version 配合 OptimisticLockerInnerInterceptor，更新时自动带上版本条件并在影响行数为零时暴露冲突。
                    """),
            new Spec("persistence", "InnoDB 聚簇索引、最左前缀与索引失效写法", """
                    InnoDB 的主键索引就是聚簇索引，叶子节点直接存放整行数据；二级索引的叶子只存放索引列与主键值，查询未覆盖的列需要回表。联合索引 (a, b, c) 遵循最左前缀，WHERE a = ? AND b > ? 只能用到 a、b 两列定位，范围条件之后的列无法继续用于缩小扫描区间，只能用于过滤。

                    典型失效写法包括：对索引列使用函数如 DATE(create_time) = ?、隐式类型转换如字符串列与数字比较、以百分号开头的 LIKE、OR 连接了未建索引的列、以及在联合索引上跳过最左列直接使用后面的列。把这些写法改成等值或范围条件，往往比新增索引更有效。

                    EXPLAIN 重点关注 type、key、rows 与 Extra 四列。type 从优到劣大致为 const、eq_ref、ref、range、index、ALL；Extra 出现 Using filesort 说明排序未走索引，出现 Using temporary 常见于分组与去重；出现 Using index 表示覆盖索引生效。联合索引的字段顺序应按区分度与查询频率安排，范围列通常放在最后。
                    """),
            new Spec("persistence", "MySQL 事务隔离级别与 MVCC 可见性判断", """
                    InnoDB 支持四种隔离级别。READ UNCOMMITTED 能读到未提交数据；READ COMMITTED 每次快照读都会生成新的 ReadView；REPEATABLE READ 在事务内首次快照读时生成 ReadView 并持续复用，是 MySQL 的默认级别；SERIALIZABLE 给读操作加锁，退化为串行执行，并发度最低。

                    MVCC 依赖隐藏列 DB_TRX_ID、DB_ROLL_PTR 与 undo log 组成的版本链。判断可见性时把记录事务号与 ReadView 中的 m_ids、min_trx_id、max_trx_id 比较，再沿版本链找到第一个可见版本。普通 SELECT 属于快照读，走 MVCC；SELECT ... FOR UPDATE、UPDATE、DELETE 属于当前读，读最新版本并加锁。

                    REPEATABLE READ 下快照读不会出现幻读，当前读仍可能出现，InnoDB 用临键锁在索引区间上加锁抑制。修改全局默认可配置 transaction-isolation 参数，也可以对单个事务执行 SET TRANSACTION ISOLATION LEVEL READ COMMITTED。选型时要权衡一致性要求与并发冲突率，而不是一律追求最高隔离级别。
                    """),
            new Spec("persistence", "行锁、间隙锁、临键锁与死锁日志排查", """
                    InnoDB 的行锁加在索引记录上，若更新条件没有命中索引，就会扫描并锁住大量记录，效果接近锁表。记录锁 Record Lock 锁住单条索引项；间隙锁 Gap Lock 锁住开区间，阻止其他事务在区间内插入；临键锁 Next-Key Lock 是记录锁与其前方间隙的组合，REPEATABLE READ 下默认使用这一粒度。

                    唯一索引上的等值命中会退化为记录锁，等值未命中则加间隙锁。插入意向锁用于协调并发插入，本身不阻塞彼此，但与间隙锁冲突。因此同一批数据被不同顺序更新时，容易因为间隙范围交叠而互相等待。

                    死锁排查用 SHOW ENGINE INNODB STATUS 查看 LATEST DETECTED DEADLOCK 段落，其中列出两个事务各自持有的锁与正在等待的锁。innodb_deadlock_detect 默认开启，检测到环后回滚代价较小的事务并返回错误码 1213。规避手段是缩短事务、让加锁顺序一致、把大事务拆小、并确保更新条件走到索引。
                    """),
            new Spec("persistence", "binlog 格式、两阶段提交与主从复制延迟", """
                    binlog 记录所有变更，格式有 STATEMENT、ROW、MIXED 三种，ROW 记录每行修改前后的镜像，复制结果确定，是默认推荐值。binlog_row_image 可取 full、minimal、noblob 控制记录量，binlog_expire_logs_seconds 控制日志保留时长，直接决定磁盘占用与可恢复的时间窗口。

                    事务提交采用两阶段提交：先写 redo log 并置为 prepare，再写 binlog，最后把 redo log 置为 commit。崩溃恢复时用 binlog 是否完整来判断该事务应提交还是回滚，从而让两份日志保持一致。sync_binlog=1 与 innodb_flush_log_at_trx_commit=1 常被称为双一配置，用吞吐换安全。

                    从库的 IO 线程拉取 binlog 写入 relay log，SQL 线程负责重放。延迟可通过 SHOW SLAVE STATUS 的 Seconds_Behind_Master 观察，并行重放可设置 replica_parallel_workers 与 replica_parallel_type=LOGICAL_CLOCK。对一致性敏感的读取应走主库，或临时等待从库位点追平后再读。
                    """),
            new Spec("persistence", "HikariCP 连接池参数与连接泄漏排查", """
                    maximumPoolSize 决定数据库并发上限，经验上按 CPU 核数乘以二到四再叠加磁盘等待来估算，配得过大反而让数据库线程互相争抢。minimumIdle 与 maximumPoolSize 设为相同值可避免连接反复创建与销毁。connectionTimeout 默认 30 秒，超时后抛 SQLTransientConnectionException，说明池子被占满。

                    idleTimeout 控制空闲连接回收，maxLifetime 必须小于数据库侧的 wait_timeout 并留出几十秒余量，否则会取到被服务端单方面关闭的死连接。keepaliveTime 定期发送心跳防止连接被中间设备回收，validationTimeout 与 connectionTestQuery 决定连通性检测方式，符合 JDBC4 规范的驱动通常不需要测试语句。

                    连接泄漏可开启 leak-detection-threshold，超过阈值会把借用栈打印出来定位未关闭的位置。监控上通过 Micrometer 暴露 hikaricp.connections.active、hikaricp.connections.pending 与 hikaricp.connections.timeout，pending 长期大于零意味着池子偏小或存在长事务占用。
                    """),
            new Spec("persistence", "深分页改写、延迟关联与慢查询定位", """
                    LIMIT 1000000, 20 会先扫描再丢弃前一百万行，代价随偏移量线性上升。游标法改写成 WHERE id > lastId ORDER BY id LIMIT 20，依赖主键有序，性能稳定但失去跳页能力，适合滚动加载与导出场景。

                    延迟关联先在覆盖索引上取主键再回表，例如 SELECT t.* FROM article t JOIN (SELECT id FROM article WHERE status = 1 ORDER BY create_time DESC LIMIT 1000000, 20) x ON t.id = x.id。若过滤列与排序列能组成 (status, create_time, id) 这样的联合索引，子查询可以完全在索引内完成，再只回表二十行。

                    慢查询靠 slow_query_log 与 long_query_time 捕获，再用 mysqldumpslow 或 pt-query-digest 聚合排序。分析时结合 EXPLAIN 的 rows 与 filtered 估算代价，优先处理 type=ALL、Using filesort、Using temporary。优化顺序是先补合适的联合索引，再改写 SQL，最后才考虑冗余字段与分表。
                    """),
            new Spec("redis", "Redis 数据类型与底层编码的转换阈值", """
                    String 底层是 SDS，除保存字节序列外还记录长度与剩余空间，追加时不必每次重新分配内存。可解析为整数的值使用 int 编码，44 字节以内的短字符串使用 embstr，超过后变为 raw，每次转换都会带来额外的内存与操作代价。

                    List 在 3.2 之后统一为 quicklist，即由多个紧凑列表节点串成的双向链表，早期节点是 ziplist，Redis 7 起改为 listpack，list-max-listpack-size 控制单个节点容量。Hash 与 ZSet 在小数据量下用 listpack，字段数超过 hash-max-listpack-entries 或单值超过 hash-max-listpack-value 就转为 hashtable 与 skiplist。Set 在元素全为整数且数量少时用 intset。

                    编码转换不可逆，一旦转成大编码就不会退回小编码，可用 OBJECT ENCODING 查看当前编码。大 key 会让单次操作阻塞主线程，应把大 Hash 拆成多个键，用 HSCAN 分批遍历，生产环境禁止执行 KEYS 与 FLUSHALL 这类全键空间操作。
                    """),
            new Spec("redis", "Cache Aside 更新策略与缓存一致性方案", """
                    读路径是查缓存命中则返回，未命中则查数据库并回填，同时设置过期时间兜底。写路径常用先更新数据库再删除缓存，而不是更新缓存，因为更新缓存会引入并发写互相覆盖，也容易写入长期无人访问的无效值。

                    先删缓存再更新数据库在并发下容易被旧值回填，可采用延迟双删，更新数据库后等待一小段时间再删一次，但等待时长难以确定。更稳的做法是订阅 binlog，用 Canal 解析变更投递到消息队列后按键删除，删除失败可以重试，把失败面收敛到消息层。

                    键空间通知配合 notify-keyspace-events 参数可以订阅过期事件，但该通知既不保证可靠也不保证及时，不能作为一致性方案的核心依赖。最终一致性场景应允许短暂脏读，用 TTL 与数据版本号限制旧值的影响范围，并在业务上避免依赖缓存做唯一性判断。
                    """),
            new Spec("redis", "RDB、AOF 与混合持久化的取舍", """
                    RDB 按 save 或 bgsave 触发快照，bgsave 通过 fork 子进程写临时文件再原子改名，利用写时复制减少主线程阻塞。优点是文件紧凑、恢复速度快，缺点是两次快照之间的写入会在宕机时丢失。

                    AOF 追加记录写命令，appendfsync 可取 always、everysec、no，默认 everysec 最多丢失约一秒数据，always 每条都刷盘但吞吐下降明显。AOF 会随命令累积持续膨胀，靠 auto-aof-rewrite-percentage 与 auto-aof-rewrite-min-size 触发重写，重写同样由子进程完成，期间新命令写入重写缓冲区。

                    Redis 4.0 起支持混合持久化 aof-use-rdb-preamble yes，重写后的 AOF 前半段是 RDB 格式，后半段是增量命令，兼顾恢复速度与丢失窗口。恢复时若开启 AOF 会优先加载 appendonly.aof，文件被截断可用 redis-check-aof 修复，因此持久化文件所在磁盘必须具备足够的写入余量。
                    """),
            new Spec("redis", "maxmemory 淘汰策略与键空间治理", """
                    设置 maxmemory 之后必须选择 maxmemory-policy。noeviction 会对写命令直接返回错误；allkeys-lru 与 allkeys-lfu 在所有键中按最近或最常使用淘汰，适合纯缓存；volatile-lru、volatile-lfu、volatile-ttl、volatile-random 只淘汰设置了过期时间的键，适合缓存与持久数据混用的键空间。

                    LRU 采用近似算法，每次采样 maxmemory-samples 个键挑出最久未使用的，默认采样五个，调大更接近真实 LRU 但更耗 CPU。LFU 按访问频次计数并随时间衰减，lfu-log-factor 控制计数增长速度，lfu-decay-time 控制衰减周期，适合有明显热点的场景。

                    键空间治理要定期用 SCAN 配合 MEMORY USAGE 找出大 key，用 INFO keyspace 观察 db0 的 keys 与 expires 数量判断过期策略是否按预期工作。键名加业务前缀便于按业务统计用量，临时缓存一律设置 TTL，从源头避免内存持续增长到被动触发淘汰。
                    """),
            new Spec("redis", "主从复制、哨兵故障转移与 Cluster 槽位", """
                    主从复制建立时从节点发送 PSYNC，主节点执行 bgsave 并把期间的写命令写入复制缓冲区，从节点加载 RDB 后接收增量命令。repl-backlog-size 决定断线重连能否做部分重同步，配得过小会让短暂的网络抖动退化为全量同步，进而引发主节点 fork 阻塞。

                    哨兵监控主从拓扑并通过投票选举，主观下线与客观下线由 quorum 参数判定，选出新主后通知其余从节点改为复制新主，客户端通过哨兵地址获取当前主节点。哨兵自身建议部署奇数个并配置认证，避免出现脑裂或无法达成多数派。

                    Cluster 把键空间划分为 16384 个槽，键名经 CRC16 取模映射到槽，跨槽的多键命令会报 CROSSSLOT，可用 hash tag 让相关键落到同一槽。集群要求至少三个主节点，MOVED 与 ASK 重定向由客户端处理，扩容时用 redis-cli --cluster reshard 迁移槽，迁移期间要容忍 ASK 跳转带来的额外往返。
                    """),
            new Spec("redis", "缓存穿透、击穿、雪崩的判定与防护", """
                    穿透指查询根本不存在的数据，缓存永远不命中，请求每次都落到数据库。防护手段包括对空结果也写入缓存并配一个较短的 TTL、在入口用布隆过滤器拦截，布隆过滤器存在误判率且不支持删除单个元素，删除只能靠计数布隆或定期重建。

                    击穿指某个热点键过期的瞬间大量并发同时回源。做法是用互斥锁让一个线程负责重建，其余线程短暂等待后重试；另一种是逻辑过期，缓存值里保存过期时间戳，读取时发现已过期就返回旧值并异步刷新，把可用性放在强一致性之前。

                    雪崩指大量键在同一时刻失效，或缓存服务整体不可用。给 TTL 加随机偏移、把热点数据分散到不同节点、增加本地一级缓存都能缓解。无论如何都应在业务侧预留降级与限流开关，否则回源流量会直接压垮数据库，把缓存故障放大成整体不可用。
                    """),
            new Spec("mq", "RabbitMQ 交换机类型与绑定路由规则", """
                    direct 交换机按 routing key 精确匹配绑定键，适合点对点任务分发；fanout 忽略路由键，把消息广播到所有绑定队列，适合配置刷新与广播通知；topic 支持通配符，星号匹配一个单词，井号匹配零个或多个单词，适合按区域或按业务维度订阅。

                    headers 交换机按消息头键值匹配，x-match 取 all 表示全部条件满足，any 表示任一满足，性能不如前三种且可观测性较差，实践中用得较少。默认交换机名为空字符串，每个队列都会自动以队列名绑定到它，因此指定队列名发送也能走通。

                    声明队列可设置 durable 持久化、exclusive 排他、autoDelete 无消费者时自动删除，以及 x-message-ttl、x-max-length、x-dead-letter-exchange 等参数。重复声明同名但参数不同的队列会返回 406 PRECONDITION_FAILED 并关闭信道，发布前应统一核对声明参数。
                    """),
            new Spec("mq", "生产者确认、mandatory 与消息可靠投递", """
                    消息从生产者到队列要跨过交换机与队列两跳，任何一跳失败都可能静默丢消息。开启 publisher-confirm-type 为 correlated 后每条消息会收到 ack 或 nack，开启 publisher-returns 为 true 并设置 mandatory 后，路由不到任何队列的消息会触发 ReturnsCallback 回调。

                    Broker 侧要求交换机与队列都是 durable，且消息 deliveryMode 为 2 才会落盘，否则重启后消息丢失。事务模式语义上可靠，但每次提交都要等待同步，吞吐下降明显，实践中更常用确认机制配合本地消息表来保证不丢。

                    发送失败应写入数据库或消息表并定时重投，重投消息需携带业务唯一号以便消费端去重。注意 ConfirmCallback 是异步回调，不能把重试逻辑写成与 convertAndSend 同步的顺序代码，正确做法是在回调里更新消息状态，再由补偿任务处理长期未确认的记录。
                    """),
            new Spec("mq", "消费者确认模式与 prefetch 流控", """
                    spring.rabbitmq.listener.simple.acknowledge-mode 可取 none、auto、manual。none 表示投递即确认，消费者抛出异常也会被当作成功，消息直接丢失；auto 由容器根据监听方法是否抛异常决定 ack 或 nack；manual 需要在方法参数中拿到 Channel 自行调用 basicAck 或 basicNack。

                    nack 时 requeue 为 true 会把消息重新放回队列，处理不当会造成死循环与 CPU 空转，正确做法是限制重试次数后转入死信队列。prefetch 即 basicQos，控制单个消费者未被确认的消息上限，设得太小吞吐不足，设得太大则会让一个消费者囤积大量消息，导致多个消费者之间负载严重不均。

                    并发消费用 concurrentConsumers 与 maxConcurrentConsumers 控制容器线程区间，容器按负载自动增减。消费端必须保证幂等，因为网络抖动、确认帧丢失、容器重启都会造成重复投递，重复是常态而不是异常，不能用唯一约束以外的假设来兜底。
                    """),
            new Spec("mq", "死信队列、延迟消息与退避重试", """
                    消息变成死信有三种情形：消费者 basicNack 且 requeue 为 false、消息 TTL 到期仍未被消费、队列达到 x-max-length 后被丢弃或被拒绝。给队列设置 x-dead-letter-exchange 与 x-dead-letter-routing-key 后，死信会被重新路由到指定交换机，便于统一收集与人工处理。

                    延迟消息可用 TTL 加死信交换机模拟，但同一队列内所有消息的 TTL 必须一致，否则队头未过期消息会挡住后面的消息，实际延迟时间被拉长。更准确的方案是安装 rabbitmq_delayed_message_exchange 插件，声明 x-delayed-type 并给消息设置 x-delay 头。

                    重试不要在原队列原地打转，应按延迟梯度拆成多个重试队列，例如五秒、三十秒、五分钟，每个队列的消费者失败后投递到下一级，超过最大次数进入死信队列并触发告警。重试消息要保留原始消息头与重试计数，方便定位是哪一级开始持续失败。
                    """),
            new Spec("mq", "消息积压定位与削峰填谷", """
                    积压要从生产与消费两端同时看。生产速率突增通常来自活动流量或上游重试风暴，消费速率下降多因下游数据库变慢、异常导致反复重投、或消费者线程被慢逻辑占满。用管理插件的队列面板或 HTTP API 观察 ready 与 unacked 的数量变化，能快速判断卡在哪一侧。

                    应急手段是临时扩容消费者，但消费者数量受 prefetch 与下游容量约束，盲目扩容只会把压力转移给数据库；也可以先把消息转存到临时队列或数据库稍后处理，优先恢复上游可用性。长期方案是把消费逻辑改成批量处理，并让下游查询走索引减少单条耗时。

                    削峰填谷的前提是接口不要求同步返回结果。下单、发券、积分结算都可以先落库并返回受理状态，再由消费者异步完成。务必给队列配置 max-length 或 TTL 与死信兜底，否则持续积压写满磁盘会造成整个 Broker 不可用，影响面会从单个业务扩散到全部消息通道。
                    """),
            new Spec("mq", "本地消息表与最终一致性补偿", """
                    跨服务写库与发消息无法放进同一个本地事务，本地消息表把两者收敛进一个数据库事务：业务数据与一条待发送消息同时落库，再由定时任务扫描未发送记录投递到 Broker，投递成功后更新状态。这样即使投递失败，消息也不会随事务一起消失。

                    消费端必须幂等，通常以业务唯一号在去重表上建唯一索引，或者先用 Redis 的 SETNX 占位再处理，处理完成后写入结果。消费失败要有重试与死信告警，多次失败后进入人工处理队列，不能无限重投消耗资源。

                    定时任务存在重复扫描与重复投递的可能，因此状态更新要带乐观锁条件，只允许从待发送流转为已发送一次。对账任务定期比对消息表与业务表，发现长期未确认的记录触发补偿。整体目标应当是最终一致，业务侧要能容忍中间状态，而不是用分布式锁强行模拟强一致。
                    """),
            new Spec("frontend", "React 渲染流程、key 与不必要的重渲染", """
                    setState 触发重新渲染时会创建新的元素树，并与上一次的 fiber 树做 diff。列表 diff 依赖 key 判断元素身份，用数组下标作 key 在插入或删除后会让复用错位，组件内部状态张冠李戴，正确做法是使用后端返回的稳定业务 id。

                    React 18 的 createRoot 开启自动批处理，promise 回调与 setTimeout 中的多次 setState 也会合并成一次渲染。父组件渲染默认会带动所有子组件，React.memo 通过浅比较可以跳过 props 未变的子树，但传入内联对象或内联函数会让浅比较永远失败，反而让 memo 失去意义。

                    定位性能问题应使用 React DevTools 的 Profiler 录制，关注 commit 阶段的真实耗时而不是渲染次数。useMemo 与 useCallback 只应用在确有昂贵计算或必须稳定引用的地方，滥用会带来依赖维护成本，而且并不保证一定减少渲染，优化要以实测数据为依据。
                    """),
            new Spec("frontend", "useEffect 依赖数组、闭包陷阱与清理函数", """
                    依赖数组决定副作用何时重新执行：空数组只在挂载后执行一次，省略数组则每次渲染都会执行，列出具体依赖则依赖变化时执行。闭包捕获的是当次渲染的变量，定时器与事件监听中读到的会一直是旧值，可以用 ref 保存最新值，或把变量列入依赖让副作用重新建立。

                    清理函数在依赖变化前与组件卸载时执行，用于 clearInterval、removeEventListener、以及通过 AbortController 取消未完成的请求。缺少清理会造成内存泄漏与重复监听，在 StrictMode 开发模式下副作用会被故意执行两次，用来提前暴露清理逻辑不完整的问题。

                    数据请求要注意竞态：先发出的慢请求可能后返回并覆盖新结果，可用 AbortController 或一个忽略标志位解决。依赖数组写不全同样会读到过期状态，ESLint 的 react-hooks/exhaustive-deps 规则能提示缺失依赖，但需要先想清楚副作用语义再决定是否禁用。
                    """),
            new Spec("frontend", "React 状态管理边界与 Redux Toolkit 实践", """
                    状态应按作用域划分：组件内部交互用 useState，跨层级共享用 Context，真正的全局业务状态才交给状态库。把页面级状态也塞进全局 store 会让组件依赖面变大，任何一次更新都可能触发无关组件渲染，也让调试与重构变得更难。

                    Redux Toolkit 用 configureStore 自动装配 thunk 与开发工具，createSlice 把 reducer 与 action 写在一起，内部基于 Immer 支持直接修改草稿状态。异步逻辑用 createAsyncThunk 或 createListenerMiddleware，pending、fulfilled、rejected 三态便于渲染加载中与错误提示。

                    派生数据用 createSelector 做记忆化，避免每次选择都返回新对象而触发无谓渲染。store 中只放可序列化的普通数据，不要放 DOM 节点、类实例或 Promise。服务端状态更适合交给 React Query 或 SWR，它们自带缓存、失效、重试与去重，比手写在 thunk 里更省事。
                    """),
            new Spec("frontend", "React Router 路由组织与代码分割", """
                    React Router 6 用 createBrowserRouter 定义路由表，嵌套路由通过 Outlet 渲染子页面，相对路径与 index 路由让布局复用更自然。路径参数用 useParams 读取，查询条件用 useSearchParams 管理，把筛选与分页状态放进 URL 可以支持刷新还原与链接分享。

                    鉴权可以放在路由 loader 中，未登录时直接 redirect 到登录页并带上回跳地址，比在组件里用 useEffect 判断更早执行，也少一次闪烁。路由级代码分割用 React.lazy 配合 Suspense，fallback 提供骨架屏，避免首屏把全部页面一次性加载进来。

                    部署到静态服务器时 History 路由需要把未知路径回退到 index.html，Nginx 用 try_files $uri $uri/ /index.html 实现。同时要避免路径末尾斜杠、大小写不一致以及基线路径配置错误导致的 404，路由常量应集中定义，禁止在组件里散落硬编码字符串。
                    """),
            new Spec("frontend", "Vite 构建配置与包体积优化", """
                    Vite 开发期用 esbuild 预构建依赖，把裸模块说明符改写为缓存目录下的真实路径，因此冷启动很快；生产构建默认用 Rollup，压缩可切换。build.rollupOptions.output.manualChunks 把 react、路由、图表库拆成独立 chunk，配合内容哈希文件名充分利用浏览器缓存。

                    体积分析用 rollup-plugin-visualizer 生成报告，按 gzip 后体积排序决定优化优先级。体积大的图表库改为按需引入，重量级日期库可以替换为更小的实现或只用原生 Intl。图片压缩与格式转换放在构建流程之外处理，避免每次构建重复计算。

                    路径别名通过 resolve.alias 配置，环境变量只暴露 VITE_ 前缀的项，并且会被打进产物，绝不能用于存放密钥。产物中 index.html 必须禁用强缓存否则用户拿不到新版本，而带哈希的静态资源应设置长期缓存，这段缓存策略要与部署侧的 Nginx 配置一起核对。
                    """),
            new Spec("frontend", "前端工程化：TypeScript 严格模式与质量门禁", """
                    tsconfig 中开启 strict、noUnusedLocals、noImplicitOverride、noFallthroughCasesInSwitch 能提前拦住大部分低级错误。类型不明确时优先用 unknown 再做类型收窄，而不是随手写 any；接口类型要与后端 DTO 对齐，可选字段用问号声明，尽量不用 as 断言绕过检查。

                    ESLint 负责代码质量与 hooks 规则，Prettier 负责格式化，两者通过 eslint-config-prettier 关闭冲突规则。提交前用 husky 加 lint-staged 只检查改动的文件，CI 中执行 tsc --noEmit 与单元测试作为合并门禁，避免把类型错误拖到运行期才暴露。

                    依赖治理要锁定版本并定期审计，用包管理器的 overrides 统一间接依赖版本，避免同一个库出现多份实例。性能预算可以写进 CI，限制首屏 chunk 的 gzip 体积，超限即失败。构建缓存复用依赖缓存目录，能显著缩短流水线时间。
                    """),
            new Spec("deploy", "多阶段 Dockerfile 构建 Spring Boot 镜像", """
                    多阶段构建把编译环境与运行环境分离。第一阶段基于 maven 与 JDK 镜像，先复制 pom.xml 执行 dependency:go-offline 以复用依赖层，再复制源码执行 package -DskipTests；第二阶段基于 JRE 镜像，只复制构建出的 jar，镜像体积可以从数百兆降到一百多兆。

                    为了命中镜像层缓存，变化频率低的指令要放在前面：依赖下载在前，源码复制在后。用 COPY --from=builder 指定来源阶段与目标路径，ENTRYPOINT 采用 exec 形式避免多一层 shell 导致信号无法传递给 JVM；需要处理僵尸进程时可以加 --init 或引入 tini。

                    进一步瘦身可选用 alpine 或 distroless 基础镜像，但要确认缺少 glibc 带来的兼容问题，健康检查用到的 curl 与字体需按需安装。.dockerignore 排除 target、node_modules、.git，否则构建上下文会膨胀并拖慢上传。镜像标签应包含版本号与提交哈希，禁止只用 latest 交付。
                    """),
            new Spec("deploy", "容器健康检查、日志驱动与常用运行参数", """
                    HEALTHCHECK 指令或 Compose 的 healthcheck 配置 test、interval、timeout、retries 与 start_period，用于区分存活与就绪两种状态。Spring Boot 3 可以配合 Actuator 的 liveness 与 readiness 探针，让编排系统在依赖尚未就绪时不把流量打进来，避免启动期大量请求失败。

                    docker run 常用参数包括 -d 后台运行、--name 命名、-p 端口映射、-v 挂载数据卷、--restart unless-stopped、-e 注入环境变量、--network 指定网络以及 --memory 与 --cpus 限制资源。重启策略与退出码有关，进程持续非零退出会被反复重启，需要设置次数上限并告警。

                    日志建议使用 json-file 驱动并配置 max-size 与 max-file 限制轮转，否则长期运行必然写满磁盘。应用应把日志输出到标准输出而非容器内文件，交给采集器统一处理。docker inspect 查看健康状态与重启次数，docker stats 观察资源占用，docker logs --since 按时间范围过滤。
                    """),
            new Spec("deploy", "Docker Compose 编排后端、数据库与缓存", """
                    Compose 文件用 services 定义应用、MySQL、Redis 三类服务，通过自定义 bridge 网络让服务名可互相解析，不必写死 IP。端口映射只暴露必要入口，数据库与缓存留在内部网络通信，避免把存储服务直接暴露到公网。

                    启动顺序用 depends_on 配合 condition: service_healthy，仅声明 depends_on 只保证容器被启动，并不保证依赖已经可用。MySQL 通过 command 传入字符集与认证插件参数，用命名卷挂载数据目录保证数据持久化，否则重新创建容器会丢库。

                    配置通过 environment 与 env_file 注入，敏感信息交给 secrets 或外部注入，不要提交进仓库。应用镜像可以用 build 指定上下文与 Dockerfile，或直接拉取仓库中的固定标签。修改配置后用 up -d 重建受影响的服务，注意 down -v 会连带删除数据卷，属于不可逆操作。
                    """),
            new Spec("deploy", "Nginx 反向代理、静态资源缓存与真实 IP", """
                    nginx.conf 中用 upstream 定义后端节点，按 least_conn 或 ip_hash 选择策略，proxy_pass 指向 upstream 名称。proxy_set_header 设置 Host、X-Real-IP、X-Forwarded-For 与 X-Forwarded-Proto 把原始信息传给后端，否则应用日志里只会记录网关地址，限流与审计也无从按真实客户端区分。

                    location 匹配优先级为精确匹配、带脱字符的前缀匹配、正则匹配、普通前缀匹配。静态资源用 expires 与 Cache-Control 设置长缓存，index.html 单独设置为不缓存或短缓存。开启 gzip 压缩文本类响应，gzip_types 需要显式列出类型，图片与压缩包不必再压。

                    上传大文件要调整 client_max_body_size，长连接与慢接口要调整 proxy_read_timeout 与 keepalive。后端维护时返回自定义错误页，配合 proxy_next_upstream 做故障切换。证书用 certbot 签发并配置自动续期，同时把 HTTP 重定向到 HTTPS，避免混合内容被浏览器拦截。
                    """),
            new Spec("deploy", "容器内存限制与 JVM 参数配合", """
                    容器通过 cgroup 限制内存，docker run 的 --memory 或 Compose 中 deploy.resources.limits.memory 设置上限。JVM 在容器内会读取 cgroup 限额作为可用内存，可用 -XX:MaxRAMPercentage=75 按比例设置堆上限，避免手写固定 Xmx 与容器限额脱节导致被杀。

                    堆外内存包括元空间、线程栈、直接内存与 JVM 自身开销，堆不能占满限额，否则容器会因总内存超限被 OOMKilled，退出码通常是 137。线程数较多时线程栈占用不可忽略，可用 -Xss 调整单栈大小；大量使用 NIO 或 Netty 时直接内存也要纳入预算。

                    排查容器内内存问题要区分是 JVM 抛出 OutOfMemoryError 还是被内核杀掉：前者看堆转储与 GC 日志，后者看 docker inspect 的 OOMKilled 标志与节点 dmesg。建议配置 -XX:+HeapDumpOnOutOfMemoryError 并把转储目录挂载到可写卷，否则容器销毁后证据一并丢失。
                    """),
            new Spec("deploy", "发布流程、版本回滚与日志聚合", """
                    一次发布包含拉取镜像、启动新实例、等待健康检查通过、切换流量四个环节。单机可用 Compose 重建容器，多机应交给编排平台做滚动更新，用 maxUnavailable 与 maxSurge 控制替换节奏。数据库变更必须与代码解耦，先执行向前兼容的迁移再发布代码，避免新旧版本同时读写不兼容的表结构。

                    回滚的前提是镜像与配置都有明确版本。保留最近若干版本的镜像标签，回滚即指向上一个标签并重启，禁止使用浮动标签。回滚前确认数据库迁移是否可逆，不可逆的变更要设计成向前兼容，必要时用双写与影子表过渡。

                    日志用 Loki 或 ELK 聚合，采集时附带容器名、镜像版本与实例标识，便于按版本过滤对比。关键链路补齐 traceId 并在网关生成与透传，排查跨服务问题时不必依赖时间戳对齐。发布后先观察错误率与 P99 延迟，确认稳定再逐步扩大流量。
                    """),
            new Spec("concurrency", "ThreadPoolExecutor 参数含义与拒绝策略", """
                    ThreadPoolExecutor 的调度逻辑是：核心线程数未满就创建新线程执行任务，核心线程满了入队，队列满后创建非核心线程直到 maximumPoolSize，仍然满则触发拒绝策略。常用队列是 ArrayBlockingQueue 与 LinkedBlockingQueue，无界队列会让 maximumPoolSize 完全失效，任务不断堆积直到内存溢出。

                    拒绝策略有四种：AbortPolicy 抛 RejectedExecutionException，由调用方感知失败；CallerRunsPolicy 让提交任务的线程自己执行，形成天然反压；DiscardPolicy 静默丢弃；DiscardOldestPolicy 丢弃队首任务后重试提交。生产环境应统计拒绝次数并对纯丢弃策略保持警惕。

                    线程工厂应自定义线程名便于在线程栈中定位，并设置 UncaughtExceptionHandler 记录未捕获异常。核心线程可开启 allowCoreThreadTimeOut 在空闲时回收。Spring 中通过 ThreadPoolTaskExecutor 配置 corePoolSize、maxPoolSize、queueCapacity、keepAliveSeconds 与 rejectedExecutionHandler。
                    """),
            new Spec("concurrency", "volatile 语义、happens-before 与双重检查锁", """
                    volatile 保证可见性与有序性：写操作会插入内存屏障并把值刷回主存，读操作会重新从主存加载并禁止与后续读写重排。它不保证复合操作的原子性，i++ 这类读改写仍需 synchronized 或 AtomicInteger 才能正确。

                    happens-before 规则包括程序顺序规则、监视器锁的解锁先于后续加锁、volatile 写先于后续的 volatile 读、线程 start 先于线程内所有操作、线程内所有操作先于 join 返回，以及这些规则的传递性。JMM 通过这些规则在没有数据竞争的前提下保证执行结果可预期，这也是判断某些写法是否安全的依据。

                    双重检查锁单例必须给实例字段加 volatile，否则可能读到构造尚未完成的对象，因为对象创建分为分配内存、初始化、赋值引用三步，第二步与第三步之间可能重排。更简洁且无需显式同步的写法是静态内部类或枚举，由类加载机制天然保证线程安全。
                    """),
            new Spec("concurrency", "ConcurrentHashMap 与 HashMap 的并发陷阱", """
                    HashMap 在多线程 put 时可能丢失更新，JDK 7 的头插法在并发扩容时还会形成环形链表，导致 get 操作陷入死循环并占满 CPU。JDK 8 改为尾插并引入红黑树，环链问题消失，但数据覆盖与 size 统计不准依然存在，因此 HashMap 始终不能跨线程共享。

                    Collections.synchronizedMap 给每个方法套上同一把锁，单次操作安全但复合操作仍需外部同步，并发度也低。ConcurrentHashMap 采用 CAS 加桶级 synchronized，读操作基本无锁，扩容时多个线程协助迁移数据。它的 size 是估计值，因为计数分散在 CounterCell 中，高并发下不保证精确。

                    putIfAbsent、computeIfAbsent、merge 是原子操作，而先 get 再 put 的写法依旧存在竞态。computeIfAbsent 的映射函数内不应再操作同一个 map，否则可能死锁。迭代器是弱一致的，遍历时不会抛 ConcurrentModificationException，但也不保证能看到遍历开始后的全部更新。
                    """),
            new Spec("concurrency", "ReentrantLock、AQS 与读写锁的选择", """
                    ReentrantLock 与 synchronized 的关键差异在于前者支持公平锁、可中断获取、tryLock 超时获取以及多个 Condition 条件队列，并且可以查询持有状态。AQS 内部维护一个 volatile 的 state 与 CLH 双向等待队列，独占模式靠 tryAcquire 修改 state，共享模式靠 tryAcquireShared 并在成功后传播唤醒后继节点。

                    ReentrantReadWriteLock 允许读读并发，读写与写写互斥，适合读多写少且读操作耗时较长的场景。写锁可以降级为读锁，做法是先持有写锁再获取读锁然后释放写锁；读锁不能升级为写锁，强行升级会造成永久等待。

                    StampedLock 提供乐观读 tryOptimisticRead 与 validate，适合读极多写极少的场景，但它不支持重入也没有条件变量。锁的选型原则是能用原子类与无锁结构解决就不加锁，必须加锁时优先 synchronized，确有公平性、超时或条件队列需求时再换用 Lock 家族。
                    """),
            new Spec("concurrency", "CompletableFuture 编排与异常处理", """
                    supplyAsync 用于有返回值的异步任务，runAsync 用于无返回值的任务，两者默认使用 ForkJoinPool.commonPool，阻塞型 IO 必须传入自定义 Executor，否则公共池线程会被占满，影响其他并行流任务。thenApply 做同类型转换，thenCompose 用于串联返回 CompletableFuture 的函数以避免出现嵌套结构。

                    thenCombine 合并两个独立任务的结果，thenAcceptBoth 消费双方结果，allOf 等待全部完成，anyOf 取最快完成的一个。要注意 allOf 本身不返回聚合结果，需要再配合 join 逐个取值；join 抛的是非受检异常，get 则要求处理受检异常。

                    异常处理用 exceptionally 做降级并返回默认值，handle 无论成功失败都会执行，whenComplete 适合记录日志但不改变结果。链上未处理的异常会在取值时抛出 CompletionException 并包装原始异常。需要超时控制可以用 orTimeout 或 completeOnTimeout，避免线程长期挂起。
                    """),
            new Spec("concurrency", "JVM 内存结构、垃圾回收器与常用调优参数", """
                    堆分新生代与老年代，新生代由 Eden 与两个 Survivor 组成，对象优先在 Eden 分配，经历一次 Minor GC 存活后年龄加一，达到阈值晋升老年代。大对象可能直接进入老年代，可用 -XX:PretenureSizeThreshold 控制阈值。TLAB 让线程在私有缓冲区内分配对象，减少指针碰撞的同步开销。

                    G1 把堆划分为大小相等的 Region，按停顿时间目标优先回收收益高的区域，用 -XX:MaxGCPauseMillis 设置目标停顿，-XX:InitiatingHeapOccupancyPercent 控制并发标记的触发水位。ZGC 与 Shenandoah 追求极低停顿，适合大堆与延迟敏感服务，代价是额外内存与吞吐损失。

                    内存溢出排查先用 jmap 或 -XX:+HeapDumpOnOutOfMemoryError 导出堆，再用 MAT 分析支配树与泄漏嫌疑对象。频繁 Full GC 的常见成因是内存泄漏、大对象反复创建、元空间不足或对象晋升过快。GC 日志建议用 -Xlog:gc* 统一输出并保留文件，方便事后回溯停顿分布。
                    """),
            new Spec("api", "REST 资源建模、HTTP 方法语义与状态码", """
                    资源用名词复数表示，/api/v1/articles/42/comments 表达某篇文章下的评论集合，动作交给 HTTP 方法表达。GET 用于查询且必须幂等，POST 用于创建通常返回 201 与 Location 头，PUT 做全量替换并保证幂等，PATCH 做部分更新，DELETE 删除成功后返回 204。避免把动词写进路径，例如用 POST /orders/42/cancel 之外的方案要考虑语义代价。

                    状态码按语义归类：400 表示请求格式或参数错误，401 表示未认证，403 表示已认证但无权限，404 表示资源不存在，409 表示状态冲突，422 常用于业务校验失败，429 表示被限流，5xx 表示服务端故障。不要一律返回 200 再把错误塞进响应体，否则网关、监控与客户端重试策略都无法按状态码工作。

                    分页参数统一为 page 与 size 并返回 total 与 hasNext，排序参数必须做字段白名单校验，防止被拼接进 ORDER BY。版本号放在路径中便于并行演进，新版本上线后旧接口应保留一段过渡期并公布下线时间。
                    """),
            new Spec("api", "JWT 鉴权流程、无状态代价与刷新机制", """
                    JWT 由 Header、Payload、Signature 三段以点号连接，Payload 只做 Base64URL 编码而非加密，任何人都能解开，因此不能存放手机号等敏感信息。服务端只需用密钥验签即可判断真伪，无需保存会话，天然适合水平扩展，但代价是无法单独让某个已签发的令牌提前失效。

                    为兼顾安全与体验，通常签发短有效期的 access token 与较长的 refresh token，刷新时校验 refresh token 是否被吊销或被轮换过。主动登出可以把未过期令牌的 jti 写入 Redis 黑名单，并设置与该令牌剩余有效期一致的 TTL，让黑名单自动收缩。

                    令牌存放位置直接决定攻击面：localStorage 会被 XSS 读取，HttpOnly Cookie 能防 XSS 但需要配合 SameSite 与 CSRF 令牌防跨站请求伪造。网关层做统一验签并把用户上下文注入请求头，业务服务只读取解析后的身份信息，不要在每个服务里重复实现解析逻辑。
                    """),
            new Spec("api", "参数校验、分组校验与失败信息收集", """
                    JSR-380 注解在字段上声明约束：@NotNull 用于任意对象，@NotBlank 用于字符串且拒绝纯空白，@Size 限制长度，@Min 与 @Max 限制数值范围，@Pattern 匹配正则，@Email 校验邮箱格式，@Positive 要求正数。嵌套对象必须加 @Valid 才会级联校验，集合元素要用容器元素约束才能逐个生效。

                    同一个 DTO 在创建与更新场景的规则不同，可用 groups 属性区分，方法参数上写 @Validated(CreateGroup.class) 指定当前分组。@Validated 标在类上并配合方法参数上的约束可以校验简单类型参数，失败时抛 ConstraintViolationException，与 @Valid 触发的 MethodArgumentNotValidException 是两条不同的处理路径。

                    自定义规则实现 ConstraintValidator，在 isValid 中编写判断并通过 initialize 读取注解参数，返回 false 时可用 message 模板给出提示。校验结果要在全局异常处理器中统一收集为字段名到消息的映射，而不是让各 Controller 各自解析 BindingResult。
                    """),
            new Spec("api", "统一响应结构、业务错误码与全局异常处理", """
                    统一响应体包含 code、message、data、timestamp 与 traceId，成功码固定为 0 或 200，业务错误码按模块分段，例如 1 开头表示用户、2 开头表示文章，便于前后端与文档共同维护。HTTP 状态码表达协议与传输层结果，业务码表达领域结果，两者不要混为一谈。

                    异常做分层设计：自定义 BizException 携带错误码与可展示消息并继承 RuntimeException，以便事务正确回滚；参数校验异常、鉴权异常各自有明确类型。@RestControllerAdvice 中按异常类型匹配最具体的处理器，都匹配不到才落到 Exception 兜底方法。

                    兜底处理器必须记录完整堆栈与请求标识，但对外只返回通用提示，避免泄露 SQL、类名与文件路径。参数错误与鉴权失败不应记为 error 级别，否则告警噪声会淹没真实故障。错误消息要能指导用户修正操作，而不是一律回复系统繁忙。
                    """),
            new Spec("api", "接口幂等设计、防重放与乐观锁", """
                    幂等要求同一请求执行多次与执行一次效果相同。查询天然幂等，创建类接口需要客户端生成唯一请求号，服务端以该请求号建唯一索引或写 Redis 占位，重复请求直接返回首次结果，避免重复下单与重复扣款。

                    分布式环境下先用 SET key value NX PX 30000 抢占位作为第一道拦截，处理完成后把结果写入缓存，键名包含业务类型与请求号。数据库唯一索引是最终防线，捕获 DuplicateKeyException 后回查已有记录并返回，不能依赖先查询再插入的判断，因为两步之间存在窗口。

                    防重放要同时校验时间戳与随机数：时间戳超出允许窗口直接拒绝，随机数在同一窗口内只允许出现一次。乐观锁用版本号字段做更新条件，影响行数为零说明版本已变化，需要重新读取或提示用户。前端按钮置灰只是体验优化，不能作为幂等保证。
                    """),
            new Spec("api", "限流算法、配额维度与 429 响应", """
                    固定窗口计数器实现简单，但在窗口切换的临界点容易出现两倍突刺；滑动窗口用有序集合记录请求时间戳并按窗口清理，精度更高但内存开销随请求量增长。令牌桶按固定速率放入令牌并允许一定突发，漏桶以恒定速率流出，更适合保护下游数据库。

                    限流维度可以是接口、用户、IP 或它们的组合，键名设计为 rate:api:userId:path 便于统计与排查。网关层做全局与路由级限流，业务层对发帖、评论、短信等敏感操作再加一层。阈值应通过压测得出并结合下游承受能力确定，而不是凭感觉填写。

                    被限流时返回 429 并带 Retry-After 头，响应体给出可读提示，避免客户端无脑重试放大流量。Redis 实现需保证原子性，用 Lua 脚本把读取、判断、写回放在一次执行中完成。集群下限流窗口的计数应落在同一分片，否则计数被分散到多个节点会让限流形同虚设。
                    """));

    static {
        if (ARTICLES.size() < 40) {
            throw new IllegalStateException("评测语料不足 40 篇：" + ARTICLES.size());
        }
    }
}
