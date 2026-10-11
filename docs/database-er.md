# 数据库 E-R 图（V24）

本图依据V1–V24 SQL及既有独立合成库结构回读。V24包含25业务表及1张Flyway元数据表；实际环境版本须单独回读，不由源码版本推断。完整字段可查 [迁移源码](../src/main/resources/db/migration)。

**实体属性只摘录主键和关系字段，完整字段见结构索引。实线仅表示实际数据库外键；虚线是服务端逻辑关联，不表示数据库强制外键。** 当前只有 category_follow.category_id → category.id，ON DELETE CASCADE。其余关联不能宣称数据库外键保护。

## 社区与治理

```mermaid
erDiagram
    USER_INFO {
        bigint id PK
        bigint pending_avatar_image_id
        tinyint status
    }
    ARTICLE {
        bigint accepted_answer_id
        bigint category_id
        varchar content_type
        bigint cover_image_id
        bigint id PK
        varchar status
        bigint user_id
    }
    COMMENT {
        bigint article_id
        bigint id PK
        bigint parent_comment_id
        varchar status
        bigint user_id
    }
    CATEGORY {
        bigint id PK
        tinyint status
    }
    USER_FOLLOW {
        bigint follower_id
        bigint following_id
        bigint id PK
    }
    CATEGORY_FOLLOW {
        bigint category_id FK
        bigint id PK
        bigint user_id
    }
    ARTICLE_FAVORITE {
        bigint article_id
        bigint id PK
        bigint user_id
    }
    ANSWER_LIKE {
        bigint comment_id
        bigint id PK
        bigint user_id
    }
    ARTICLE_AUDIT_RECORD {
        bigint article_id
        bigint auditor_id
        bigint id PK
    }
    NOTIFICATION {
        bigint article_id
        bigint id PK
        bigint receiver_id
        bigint sender_id
        varchar source_message_id
    }
    CONTENT_REPORT {
        bigint handler_id
        bigint id PK
        bigint reporter_id
        varchar status
        bigint target_id
        bigint target_owner_id
        varchar target_type
    }
    IMAGE_RESOURCE {
        varchar content_type
        bigint id PK
        bigint owner_id
        bigint reviewer_id
        varchar status
        bigint target_id
    }
    USER_INFO ||..o{ ARTICLE : "user_id"
    USER_INFO ||..o{ COMMENT : "user_id"
    ARTICLE ||..o{ COMMENT : "article_id"
    COMMENT ||..o{ COMMENT : "parent_comment_id 单层回复"
    CATEGORY ||..o{ ARTICLE : "category_id"
    USER_INFO ||..o{ USER_FOLLOW : "follower_id / following_id"
    USER_INFO ||..o{ CATEGORY_FOLLOW : "user_id"
    CATEGORY ||--o{ CATEGORY_FOLLOW : "category_id FK CASCADE"
    USER_INFO ||..o{ ARTICLE_FAVORITE : "user_id"
    ARTICLE ||..o{ ARTICLE_FAVORITE : "article_id"
    USER_INFO ||..o{ ANSWER_LIKE : "user_id"
    COMMENT ||..o{ ANSWER_LIKE : "comment_id 顶层回答"
    ARTICLE ||..o{ ARTICLE_AUDIT_RECORD : "article_id"
    USER_INFO ||..o{ NOTIFICATION : "receiver_id / sender_id"
    ARTICLE ||..o{ NOTIFICATION : "article_id"
    USER_INFO ||..o{ CONTENT_REPORT : "reporter_id / handler_id"
    ARTICLE ||..o{ CONTENT_REPORT : "ARTICLE 类 target_id"
    COMMENT ||..o{ CONTENT_REPORT : "COMMENT 类 target_id"
    USER_INFO ||..o{ IMAGE_RESOURCE : "owner_id / reviewer_id"
    ARTICLE ||..o{ IMAGE_RESOURCE : "ARTICLE_COVER target_id"
```

## 文字审核

```mermaid
erDiagram
    TEXT_RULE_REVISION {
        tinyint id PK
        bigint version
    }
    TEXT_RULE {
        bigint id PK
    }
    TEXT_RULE_OPERATION {
        bigint actor_id
        bigint id PK
        bigint rule_id
        bigint rule_version
    }
    TEXT_REVIEW_RECORD {
        bigint id PK
        bigint reviewer_id
        bigint rule_version
        varchar status
        bigint target_id
        varchar target_type
    }
    TEXT_RULE ||..o{ TEXT_RULE_OPERATION : "rule_id；操作保存快照"
    TEXT_RULE_REVISION ||..o{ TEXT_RULE_OPERATION : "版本计数与 rule_version"
    TEXT_RULE_REVISION ||..o{ TEXT_REVIEW_RECORD : "rule_version 快照"
```

## AI辅助

```mermaid
erDiagram
    AI_CONVERSATION {
        bigint id PK
        bigint user_id
    }
    AI_MESSAGE {
        bigint conversation_id
        bigint id PK
    }
    AI_KB_DOCUMENT {
        bigint article_id
        bigint author_id
        bigint category_id
        bigint id PK
        varchar status
    }
    AI_KB_CHUNK {
        bigint article_id
        bigint document_id
        bigint id PK
        varchar vector_id
    }
    AI_MODERATION_RECORD {
        bigint author_id
        bigint id PK
        bigint target_id
        varchar target_type
    }
    AI_INSIGHT_REPORT {
        bigint id PK
        varchar status
    }
    AI_RECOMMEND_LOG {
        bigint article_id
        bigint id PK
        bigint source_article_id
        bigint user_id
    }
    AI_EXECUTION_TRACE {
        bigint conversation_id
        bigint id PK
        bigint ref_id
        varchar status
        varchar trace_id
        bigint user_id
    }
    AI_USAGE_STAT {
        bigint id PK
        varchar trace_id
        bigint user_id
    }
    AI_CONVERSATION ||..o{ AI_MESSAGE : "conversation_id"
    AI_CONVERSATION ||..o{ AI_EXECUTION_TRACE : "conversation_id 可空"
    AI_KB_DOCUMENT ||..o{ AI_KB_CHUNK : "document_id"
    AI_EXECUTION_TRACE ||..o{ AI_USAGE_STAT : "trace_id 逻辑关联"
```

## 跨领域与约束说明

AI会话/用量/推荐中的 user_id 逻辑指向 user_info；知识库文档/分块及推荐 article_id 指向 article。AI审核 target_type/target_id 和文字待办 target_type/target_id 为多态目标，不能画成单个强制外键。图片元数据 target_id 依 kind 关联头像用户或封面文章，article.cover_image_id 与用户头像字段为逻辑引用。

category_follow 唯一键为 (user_id,category_id)，answer_like 为 (user_id,comment_id)，不能把两列分别当成单列唯一。既有收藏、用户关注、待处理举报及 notification.source_message_id 等唯一约束以历史 SQL 为准。用户删除/内容可见性等服务行为不能由虚线推断级联。

text_rule_revision 保存规则版本；text_rule_operation 保存操作快照，删除规则后历史快照仍有意义。flyway_schema_history 是迁移元数据，包含 installed_rank/version/checksum/success 等，不参与业务实体关系。

历史 cover_url、avatar 等字段仍可能在表中，不能据此声称新接口允许外链图片。
