# 单机运行与配置

适用于新建、空数据库和合成数据。Java17、Node22、Maven Wrapper、MySQL8、Redis Stack、RabbitMQ；后端/前端依赖版本按现有pom及package-lock，不自动升级。

1. 在新目录/专属Compose项目中使用.env.example生成私有.env，替换数据库、MQ和JWT占位值。没有收费模型调用时保留非空disabled-placeholder模型占位符并保持全部AI/写工具开关false，不能使用真实API key进行本轮验证。
2. 检查端口冲突、项目名、卷归属和目标库，确认该环境独立；现有业务/演示环境升级需另行批准。模板Compose仅作为配置，不自动部署。
3. 空库首次Flyway执行V1–V24；必须实际回读flyway_schema_history确认版本。禁止为运行示例清理、repair或迁移现有数据库。
4. 前端/api经代理访问后端；健康、登录、公开列表需实际核验后再演示。已有ONNX缓存可复用只读模型文件，首次缓存可能下载。

默认测试 `./mvnw -s maven-settings.xml test` 不需要启动基础服务。完整测试使用[独立测试机制](testing.md)的专属端口与数据库，不能加载业务.env。

可选HTTPS模板和证书工具保持在源码中，本候选不生成信任、不安装证书、不跨设备部署。
