# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom，利用层缓存避免每次改代码都重新下载依赖
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 以非 root 运行
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/data/files && chown -R app:app /app
USER app

COPY --from=build /build/target/*.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
