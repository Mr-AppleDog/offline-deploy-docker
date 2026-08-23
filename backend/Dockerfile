# 构建机只需要 Docker，不需要预装 JDK 或 Maven。
ARG DOCKER_REGISTRY=docker.io
FROM ${DOCKER_REGISTRY}/library/maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM ${DOCKER_REGISTRY}/library/eclipse-temurin:17-jre-alpine
WORKDIR /app
# 打包 Worker 需要访问 Git、SSH、Docker CLI、Buildx、Compose 和 tar。
# 挂载 Docker Socket 等同于授予构建容器主机级权限，只能部署在受控构建机。
RUN apk add --no-cache bash git openssh-client tar gzip docker-cli docker-cli-buildx docker-cli-compose \
    && mkdir -p /data /workspace
COPY --from=build /workspace/target/*.jar /app/app.jar
ENV KUNLUN_PLATFORM_DATA_DIR=/data \
    KUNLUN_PROJECT_ROOT=/workspace
VOLUME ["/data", "/workspace"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
