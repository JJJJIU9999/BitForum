# 第一阶段：用 Maven 镜像在容器里从源码打包项目
FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /app

# 先复制 pom.xml，方便 Docker 缓存依赖下载层
COPY pom.xml .
COPY maven-settings.xml /root/.m2/settings.xml

# 再复制源码
COPY src ./src

# 打包项目，跳过测试；测试仍然在本机用 mvn test 单独跑
RUN mvn clean package -DskipTests

# 第二阶段：用更轻量的 JRE 镜像运行 JAR
FROM eclipse-temurin:17-jre

WORKDIR /app

# 从 builder 阶段复制打包好的 JAR
COPY --from=builder /app/target/bit-forum-spring-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]