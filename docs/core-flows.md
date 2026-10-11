# 核心业务流程（当前实现）

本图描述源码行为；本轮执行证据和未验证事项单列，不将流程图视为测试通过。

## 内容审核与公开

```mermaid
flowchart TD
    Author[登录作者创建文章或问题] --> Validate[DTO / 业务校验与站内封面约束]
    Validate --> Draft[保存草稿 DRAFT]
    Validate --> Pending[提交为 PENDING]
    Draft --> Pending
    Pending --> Admin[管理员审核]
    Admin --> Gate{封面是否可公开及当前文字规则检查}
    Gate -->|不满足门禁| RejectRequest[稳定业务拒绝 / 保持待审]
    Gate -->|驳回| Rejected[REJECTED 并保存原因]
    Rejected --> Draft
    Gate -->|确认通过| Published[PUBLISHED 与审核记录]
    Published --> Public[公开列表 / 详情 / 话题 / 动态可见]
    Published --> MQ[异步通知与知识库触发]
    Published --> Offline[管理员下架 OFFLINE]
```

管理员可人工确认文字规则命中；系统自动审核受关闭的自动放行开关和人工复核约束。MQ 发布与本地事务之间没有 Outbox，图中不声称原子提交。

## 回答、点赞、回复与采纳

```mermaid
flowchart TD
    Question[已公开 QUESTION] --> Answer[登录用户发表顶层回答]
    Answer --> Text{文字规则}
    Text -->|命中| Review[人工待审 / 本人私有可见]
    Text -->|允许公开或人工批准| Open[公开回答]
    Review -->|批准且父对象仍公开| Open
    Open --> Like[其他用户点赞 / 取消]
    Open --> Reply[单层回复，复用文字审核]
    Open --> Accept[问题作者采纳 / 更换 / 取消]
    Accept --> Ref[article.accepted_answer_id]
    Reply --> Boundary[公开父回答与文章门禁 / 对应通知和举报]
```

禁止把回复采纳为回答；未公开内容不进入公共列表，父对象失效有专门限制。

## 话题关注与混合动态

```mermaid
flowchart TD
    User[登录用户] --> Topic[关注启用话题 / 幂等取消]
    User --> Author[关注作者]
    Topic --> Sources[读取当前关注来源]
    Author --> Sources
    Sources --> Query[只选 PUBLISHED / 类型筛选]
    Query --> Union[作者与话题来源合并去重]
    Union --> Cursor[时间与ID游标 / pageSize+1]
    Cursor --> Page[返回 records / hasMore / nextCursor]
    Page --> Next[加载下一批；失败保留已有内容]
    Next --> Cursor
    Topic --> Disabled[停用话题从公开入口退出；个人列表仍可取消]
```

动态不是预生成消息收件箱；排序/游标由服务端控制，前端刷新或切换类型重新读取首批。

## 图片审核与举报

```mermaid
flowchart TD
    Upload[登录上传头像 / 封面] --> Check[大小 / 类型 / 内容 / 路径校验]
    Check --> Private[私有文件与 PENDING 元数据]
    Private --> ImageAdmin[管理员批准 / 驳回]
    ImageAdmin --> PublicImage[批准后公开；头像/封面按业务绑定]
    ImageAdmin --> Denied[驳回，保留原因]
    Report[登录举报公开的他人对象] --> Unique[待处理重复约束]
    Unique --> ReportAdmin[管理员成立 / 驳回]
    ReportAdmin --> Readback[处理记录 / 报告者回读]
```

文件 IO 故障属于内部错误；事务回滚清理保留。举报处理本身不自动下架文章或删除评论。
