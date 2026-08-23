# 阶段1：依赖下载发生在联网构建机的 Docker 容器内。
ARG DOCKER_REGISTRY=docker.io
FROM ${DOCKER_REGISTRY}/library/node:20.19-alpine3.22 AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# 阶段2：nginx 托管静态文件（离线可运行，体积小）
FROM ${DOCKER_REGISTRY}/library/nginx:1.28-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
