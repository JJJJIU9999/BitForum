# 独立测试与CI

源码分类清单保留在[Finalization测试清单](graduation/finalization/test-inventory.json)，执行名单为scripts/test-sets；`python3 scripts/test_inventory.py`检查漂移。

|集合|类数|执行规则|
|---|---:|---|
|unit|32|默认mvn test，Mockito/standalone MockMvc/临时文件/纯逻辑，无基础服务|
|integration|61|实际Spring及MySQL/Redis/MQ、本地ONNX，确定性故障注入|
|cm22|2|专属库，V22→23及空库迁移|
|cm23|1|专属库V23阅读回归|
|cm24|2|专属库V24迁移/并发/动态回归|
|external-ai|11|显式关闭真实模型，仅记录跳过，不计通过|
|optional-probe|7|额外探针/评测需独立选择与授权，未运行不计通过或伪造跳过|
|support|3|测试辅助类，不是用例|

完整CI约定集合为unit/integration/cm22/cm23/cm24，另列模型跳过报告；旧自动化类没有通过排除来制造绿色CI。看板测试从全上下文改为真实拦截器+Advice独立MVC，原3项保留并加3类内部故障。

## 连接前保护

专用ApplicationContextInitializer先于Hikari/Flyway/Redis/MQ创建校验目标。只允许显式隔离许可、127.0.0.1:23306、四个专属测试库与许可URL参数；手工DROP/重建测试另行校验fresh/upgrade或UUID目标，在第一次连接之前执行。

[测试Compose](graduation/finalization/test-compose.yml)只绑定本机23306/26379/25672，runner先bind确认空闲，再创建随机独立项目/新卷，核对ID/归属/映射，不读取业务.env。GitHub service containers使用相同端口与job服务ID，不放宽原隔离规则。

完整服务测试模型地址固定为不可达回环9端口、占位key；真实模型开关关闭。Redis索引是新实例专用前缀。每次新报告目录，各集合分别统计通过/失败/错误/跳过；缺类、无用例或必跑集合跳过会失败。

原始日志/系统属性/完整JUnit保留私有输出，公开结果仅结构化统计与脱敏XML。runner只清理自己的Compose项目/卷/网络，并核对受保护容器稳定字段。

## 命令

```sh
./scripts/run-backend-tests.sh
python3 scripts/run-isolated-tests.py
```

完整测试需已有对应Docker镜像，脚本使用pull never且不构建应用镜像。测试不是部署；模型/可选探针未执行需清晰标注。[当前候选结果](test-results.md)

## V25候选的测试边界

应用服务集合使用V25，以匹配当前Article实体；C-M22与C-M24的历史迁移测试分别明确固定目标23与24，保留原迁移断言，不把后续迁移混入历史版本计数。V1–V24迁移文件字节不变。unit清单新增UTC、Markdown和聊天保护检查，未移除或跳过旧必跑类；`test_inventory.py`要求源码、JSON目录及执行名单一致。

2026-10-17本地准备仅运行unit、完整前端及构建，没有启动Docker或数据库。2026-10-18产品代码已通过GitHub runner的完整integration/cm22/cm23/cm24集合，收费模型按策略跳过；没有调用真实模型。历史结果见[V24独立验证](test-results.md)，本地准备见[20261017](updates/20261017.md)，最新提交与远端计数见[发布记录](updates/20261018.md)。

公开CI保留已修复的Step级`job.services.*.id`，完整服务报告写入`target/finalization/<run>/summary/`，与artifact匹配；原始日志及系统属性不提交到Git。
