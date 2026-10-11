# BitForum

Java 17 / Spring Boot 3.4.5 与 React 19 实现的社区内容审核、互动治理和可选AI毕业设计。面向单机学习与答辩演示，不宣称公网生产能力或生产性能。

## 已实现

- JWT/BCrypt注册登录、用户资料/昵称、人工审核头像、公开主页、用户关注及通知。
- 文章与问题的草稿、待审、公开、驳回、下架；分类搜索、图片封面审核、作者与管理员可见性门禁。
- 文章点赞/收藏/评论、回答采纳与更换/取消；回答点赞和单层回复（C-M22）、阅读与昵称呈现（C-M23）。
- 话题目录/搜索/关注/我的话题；作者与话题双来源的公开关注动态、游标翻批及去重（C-M24）。
- 文字规则与人工待办/复扫、图片人工审核、举报处理、后台管理/统计看板和健康检查。
- 可选RAG问答、站内工具、审核辅助、运营洞察、相关推荐，执行轨迹/用量/预算/降级。默认运行示例关闭模型与写工具。

## 架构与数据

Spring MVC + MyBatis-Plus；MySQL 8 / Flyway V1–V24；Redis Stack/RediSearch；RabbitMQ；本地图片文件；Spring AI及已有ONNX嵌入模型。V24新增category_follow，V23支持回答互动。源码迁移版本不代表任何已有数据库已升级。

[系统架构](docs/architecture.md) · [E-R图](docs/database-er.md) · [核心流程](docs/core-flows.md) · [运行说明](docs/running.md) · [测试与CI](docs/testing.md) · [技术边界](docs/limitations.md)

## 验证与演示

- JDK17：`./mvnw -s maven-settings.xml test` 执行真正无需基础服务的集合。
- 完整服务集合：`python3 scripts/run-isolated-tests.py` 创建专属实例，禁止连接业务数据库。
- 前端：在frontend内按现有锁文件执行 `npm ci`、`npm test`、`npm run build`。
- [独立候选验证结果](docs/test-results.md) 分集合报告通过/失败/错误/跳过，未执行项不得计通过。GitHub Actions配置存在不代表远端已通过。

[合成截图来源](docs/images/README.md)：

![我的话题](docs/images/my-topics.jpg)

这些图仅展示标明来源的合成页面，不代替当前构建的完整浏览器验收。

## 公开快照与历史

本仓库是 BitForum V24 的独立公开快照，只保留源码、工程文件及审定公开材料。Git 历史仅有一个无父节点的初始提交，不包含原开发仓库或来源发布分支的历史。测试记录与合成截图的来源、执行范围及限制见对应文档；本地准备完成不代表已发布到 GitHub。
