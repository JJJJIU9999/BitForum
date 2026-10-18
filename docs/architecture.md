# 系统架构（源码视图）

对应当前V25候选源码的单机架构；不是实时部署拓扑。单机单体，收费 AI 为可选依赖，本轮关闭。

```mermaid
flowchart LR
    Browser[浏览器 React / Vite] -->|HTTP /api + Bearer JWT| MVC[Spring MVC 与鉴权拦截器]
    MVC --> Service[社区与治理 Service]
    MVC --> AI[AI 会话 / Agent 路由]
    Service --> SQL[MyBatis-Plus]
    SQL --> MySQL[(MySQL 8 / Flyway V1–V25)]
    Service --> Redis[(Redis Stack 指标与热度)]
    Service --> Files[本地私有 / 公开图片文件]
    Service --> MQ[(RabbitMQ)]
    MQ --> Listeners[通知 / 审核辅助 / 知识库监听]
    Listeners --> Service
    Listeners --> AI
    AI --> RAG[RAG 与本地 ONNX]
    RAG --> Vector[(Redis Stack 向量索引)]
    RAG --> MySQL
    AI -. 可选收费调用 .-> Model[DeepSeek]
    AI --> Trace[轨迹 / 用量 / 预算 / 降级]
    Trace --> MySQL
    MVC --> Error[Result 与全局异常处理]
```

## 关键边界
- MySQL 保存业务事实，Redis 保存指标/热度/向量；定时指标同步不是强一致复制。
- 图片内容在磁盘，image_resource 保存审核/归属元数据；头像和封面遵循各自公开门禁。
- RabbitMQ 触发异步通知/辅助链路，不能画成事务提交与 Broker 的原子提交；尚无 Outbox。
- 推荐融合排序由后端决定，模型只生成理由；模型失败不应阻断主社区业务。
- 认证仍为 JWT + 拦截器，并非完整 Spring Security 框架。测试中新专属三服务是验证拓扑，不等于旧环境部署。

独立测试机制见 [测试说明](testing.md)，运行边界见 [运行说明](running.md)。
