package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.domain.Models;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ComposeRenderer {
    public String middleware(ProfileService.ResolvedProfile resolved, Map<String, String> versions) {
        Models.DeploymentProfile p = resolved.profile();
        return """
                name: kunlun-middleware

                # 由 Kunlun 离线交付平台生成。该文件含站点专属凭据，必须以 0600 保存。
                x-kunlun-mysql-user: &kunlun-mysql-user %s
                x-kunlun-mysql-password: &kunlun-mysql-password %s
                x-kunlun-redis-password: &kunlun-redis-password %s
                x-kunlun-rabbitmq-user: &kunlun-rabbitmq-user %s
                x-kunlun-rabbitmq-password: &kunlun-rabbitmq-password %s
                x-kunlun-minio-user: &kunlun-minio-user %s
                x-kunlun-minio-secret: &kunlun-minio-secret %s

                x-common: &common
                  platform: linux/amd64
                  pull_policy: never
                  restart: unless-stopped
                  networks: [kunlun-net]
                  logging:
                    driver: local
                    options: { max-size: 100m, max-file: "3" }

                services:
                  mysql:
                    <<: *common
                    image: mysql:%s
                    environment:
                      MYSQL_ROOT_PASSWORD: %s
                      MYSQL_DATABASE: %s
                      MYSQL_USER: *kunlun-mysql-user
                      MYSQL_PASSWORD: *kunlun-mysql-password
                      TZ: %s
                    volumes:
                      - /opt/Kunlun/middleware/mysql/data:/var/lib/mysql:Z
                      - /opt/Kunlun/middleware/mysql/conf.d:/etc/mysql/conf.d:ro,Z
                      - /opt/Kunlun/middleware/mysql/init:/docker-entrypoint-initdb.d:ro,Z
                    healthcheck:
                      test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1 -uroot -p\"$$MYSQL_ROOT_PASSWORD\" --silent"]
                      interval: 10s
                      timeout: 5s
                      retries: 18
                      start_period: 40s

                  redis:
                    <<: *common
                    image: redis:%s
                    environment:
                      REDIS_PASSWORD: *kunlun-redis-password
                      TZ: %s
                    command: ["sh", "-ec", "exec redis-server --requirepass \"$$REDIS_PASSWORD\" --appendonly yes"]
                    volumes: [/opt/Kunlun/middleware/redis/data:/data:Z]
                    healthcheck:
                      test: ["CMD-SHELL", "redis-cli --no-auth-warning -a \"$$REDIS_PASSWORD\" ping | grep -q PONG"]
                      interval: 10s
                      timeout: 5s
                      retries: 12

                  rabbitmq:
                    <<: *common
                    image: rabbitmq:%s
                    hostname: rabbitmq
                    environment:
                      RABBITMQ_DEFAULT_USER: *kunlun-rabbitmq-user
                      RABBITMQ_DEFAULT_PASS: *kunlun-rabbitmq-password
                      RABBITMQ_DEFAULT_VHOST: %s
                      TZ: %s
                    ports: [127.0.0.1:15672:15672]
                    volumes: [/opt/Kunlun/middleware/rabbitmq/data:/var/lib/rabbitmq:Z]
                    healthcheck:
                      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
                      interval: 10s
                      timeout: 10s
                      retries: 18
                      start_period: 40s

                  minio:
                    <<: *common
                    image: minio/minio:%s
                    environment:
                      MINIO_ROOT_USER: *kunlun-minio-user
                      MINIO_ROOT_PASSWORD: *kunlun-minio-secret
                      TZ: %s
                    command: server /data --console-address ":9001"
                    ports: [127.0.0.1:9001:9001]
                    volumes: [/opt/Kunlun/middleware/minio/data:/data:Z]
                    healthcheck:
                      test: ["CMD", "mc", "ready", "local"]
                      interval: 10s
                      timeout: 5s
                      retries: 18
                      start_period: 20s

                networks:
                  kunlun-net:
                    external: true
                    name: kunlun-net
                """.formatted(
                yaml(p.mysqlUsername), yaml(resolved.mysqlPassword()), yaml(resolved.redisPassword()),
                yaml(p.rabbitmqUsername), yaml(resolved.rabbitmqPassword()), yaml(p.minioAccessKey),
                yaml(resolved.minioSecretKey()), version(versions, "mysql"), yaml(resolved.mysqlRootPassword()),
                yaml(p.mysqlDatabase), yaml(p.timezone), version(versions, "redis"), yaml(p.timezone),
                version(versions, "rabbitmq"), yaml(p.rabbitmqVhost), yaml(p.timezone),
                version(versions, "minio"), yaml(p.timezone));
    }

    public String application(ProfileService.ResolvedProfile resolved, String appKey,
                              String backendVersion, String frontendVersion,
                              String backendHealthPath, String frontendHealthPath) {
        Models.DeploymentProfile p = resolved.profile();
        return """
                name: kunlun-app

                # 由 Kunlun 离线交付平台生成。必须与在线 middleware Compose 的凭据一致。
                x-kunlun-mysql-user: &kunlun-mysql-user %s
                x-kunlun-mysql-password: &kunlun-mysql-password %s
                x-kunlun-redis-password: &kunlun-redis-password %s
                x-kunlun-rabbitmq-user: &kunlun-rabbitmq-user %s
                x-kunlun-rabbitmq-password: &kunlun-rabbitmq-password %s
                x-kunlun-minio-user: &kunlun-minio-user %s
                x-kunlun-minio-secret: &kunlun-minio-secret %s

                x-common: &common
                  platform: linux/amd64
                  pull_policy: never
                  restart: unless-stopped
                  networks: [kunlun-net]
                  logging:
                    driver: local
                    options: { max-size: 100m, max-file: "3" }

                services:
                  backend:
                    <<: *common
                    image: %s-backend:%s
                    environment:
                      SPRING_DATASOURCE_URL: %s
                      SPRING_DATASOURCE_USERNAME: *kunlun-mysql-user
                      SPRING_DATASOURCE_PASSWORD: *kunlun-mysql-password
                      SPRING_DATA_REDIS_HOST: redis
                      SPRING_DATA_REDIS_PORT: 6379
                      SPRING_DATA_REDIS_PASSWORD: *kunlun-redis-password
                      SPRING_DATA_REDIS_DATABASE: %d
                      SPRING_RABBITMQ_HOST: rabbitmq
                      SPRING_RABBITMQ_PORT: 5672
                      SPRING_RABBITMQ_USERNAME: *kunlun-rabbitmq-user
                      SPRING_RABBITMQ_PASSWORD: *kunlun-rabbitmq-password
                      SPRING_RABBITMQ_VIRTUAL_HOST: %s
                      MINIO_ENDPOINT: http://minio:9000
                      MINIO_ACCESS_KEY: *kunlun-minio-user
                      MINIO_SECRET_KEY: *kunlun-minio-secret
                      MINIO_BUCKET: %s
                      JAVA_TOOL_OPTIONS: %s
                      TZ: %s
                    healthcheck:
                      test: ["CMD", "wget", "-q", "-O", "/dev/null", %s]
                      interval: 10s
                      timeout: 5s
                      retries: 18
                      start_period: 30s

                  frontend:
                    <<: *common
                    image: %s-frontend:%s
                    depends_on:
                      backend: { condition: service_healthy }
                    ports: [%d:80]
                    healthcheck:
                      test: ["CMD", "wget", "-q", "-O", "/dev/null", %s]
                      interval: 10s
                      timeout: 5s
                      retries: 12

                networks:
                  kunlun-net:
                    external: true
                    name: kunlun-net
                """.formatted(
                yaml(p.mysqlUsername), yaml(resolved.mysqlPassword()), yaml(resolved.redisPassword()),
                yaml(p.rabbitmqUsername), yaml(resolved.rabbitmqPassword()), yaml(p.minioAccessKey),
                yaml(resolved.minioSecretKey()), appKey, backendVersion,
                yaml("jdbc:mysql://mysql:3306/" + p.mysqlDatabase + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=" + p.timezone + "&allowPublicKeyRetrieval=true"),
                p.redisDatabase, yaml(p.rabbitmqVhost), yaml(p.minioBucket), yaml(p.javaOptions), yaml(p.timezone),
                yaml("http://127.0.0.1:8080" + backendHealthPath), appKey, frontendVersion, p.frontendPort,
                yaml("http://127.0.0.1" + frontendHealthPath));
    }

    private String version(Map<String, String> values, String component) {
        String value = values.get(component);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少 " + component + " 制品版本");
        return value;
    }

    private String yaml(String value) {
        if (value == null) value = "";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }
}
