package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.domain.Models;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComposeRendererTest {
    private final ComposeRenderer renderer = new ComposeRenderer();

    @Test
    void rendersIndependentCredentialsAndMixedApplicationVersions() {
        Models.DeploymentProfile profile = new Models.DeploymentProfile();
        profile.id = "site-a";
        profile.revision = 3;
        profile.mysqlDatabase = "kunlun_app";
        profile.mysqlRootUsername = "root";
        profile.mysqlUsername = "app_user";
        profile.redisDatabase = 2;
        profile.rabbitmqUsername = "rabbit_user";
        profile.rabbitmqVhost = "/kunlun";
        profile.minioAccessKey = "minioadmin";
        profile.minioBucket = "kunlun-app";
        profile.frontendPort = 8088;
        profile.timezone = "Asia/Shanghai";
        profile.javaOptions = "-Xms128m -Xmx512m";
        ProfileService.ResolvedProfile resolved = new ProfileService.ResolvedProfile(profile,
                "root-password-123", "mysql-password-123", "redis-password-123",
                "rabbit-password-123", "minio-password-123");

        String middleware = renderer.middleware(resolved, Map.of(
                "mysql", "8.4.11", "redis", "8.2.8", "rabbitmq", "4.3.4-management",
                "minio", "RELEASE.2025-07-18T21-56-31Z"));
        String application = renderer.application(resolved, "kunlun-app", "1.1.1", "1.1.2",
                "/actuator/health", "/");

        assertThat(middleware).contains("x-kunlun-mysql-password", "mysql-password-123",
                "redis-password-123", "rabbit-password-123", "minio-password-123",
                "image: mysql:8.4.11", "platform: linux/amd64");
        assertThat(application).contains("image: kunlun-app-backend:1.1.1",
                "image: kunlun-app-frontend:1.1.2", "SPRING_DATA_REDIS_DATABASE: 2",
                "8088:80", "http://127.0.0.1:8080/actuator/health");
    }
}
