# 构建阶段:context 是仓库根(需 COPY deploy/docs 等静态资源进镜像,
# 使 k3s 下 KUNLUN_PROJECT_ROOT=/workspace 可读,不再依赖宿主机挂载仓库)。
ARG DOCKER_REGISTRY=docker.io
FROM ${DOCKER_REGISTRY}/library/maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY backend/pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY backend/src ./src
RUN mvn -B -DskipTests package

FROM ${DOCKER_REGISTRY}/library/eclipse-temurin:17-jre-alpine
WORKDIR /app
# 打包 Worker 需要访问 Git、SSH、Docker CLI、Buildx、Compose 和 tar。
# 挂载 Docker Socket 等同于授予构建容器主机级权限，只能部署在受控构建机。
RUN apk add --no-cache bash git openssh-client tar gzip docker-cli docker-cli-buildx docker-cli-compose \
    && mkdir -p /data /workspace
COPY --from=build /build/target/*.jar /app/app.jar
# 烘焙部署资源到 /workspace,使 BuildWorker 组装离线包时读得到 deploy/scripts、docs 等。
COPY deploy /workspace/deploy
COPY docs /workspace/docs
COPY README.md /workspace/README.md
COPY 部署手册.md /workspace/部署手册.md
ENV KUNLUN_PLATFORM_DATA_DIR=/data \
    KUNLUN_PROJECT_ROOT=/workspace
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]