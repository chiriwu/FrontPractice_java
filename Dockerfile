# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# 先复制 pom.xml 并预下载依赖，利用 Docker 层缓存加速后续构建
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app
COPY --from=build --chown=app:app /workspace/target/*.jar app.jar

USER app
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
