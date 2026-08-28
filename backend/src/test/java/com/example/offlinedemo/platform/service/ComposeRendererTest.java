package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.catalog.CatalogEntry;
import com.example.offlinedemo.platform.catalog.MiddlewareCatalog;
import com.example.offlinedemo.platform.domain.Models;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComposeRendererTest {
    private final ComposeRenderer renderer = new ComposeRenderer();
    private final MiddlewareCatalog catalog = catalog();

    private static MiddlewareCatalog catalog() {
        MiddlewareCatalog catalog = new MiddlewareCatalog(new ObjectMapper().findAndRegisterModules());
        try { catalog.load(); } catch (Exception e) { throw new RuntimeException(e); }
        return catalog;
    }

    private ProfileService.ResolvedProfile fourMiddlewareProfile() {
        Models.DeploymentProfile profile = new Models.DeploymentProfile();
        profile.id = "site-a";
        profile.revision = 3;
        profile.frontendPort = 8088;
        profile.timezone = "Asia/Shanghai";
        profile.javaOptions = "-Xms128m -Xmx512m";
        profile.middleware = List.of(
                mw("mysql", Map.of("rootPassword", "root-password-123", "database", "kunlun_app",
                        "user", "app_user", "password", "mysql-password-123")),
                mw("redis", Map.of("password", "redis-password-123", "database", "2")),
                mw("rabbitmq", Map.of("user", "rabbit_user", "password", "rabbit-password-123", "vhost", "/kunlun")),
                mw("minio", Map.of("user", "minioadmin", "secret", "minio-password-123", "bucket", "kunlun-app")));
        Map<String, Map<String, String>> credentials = new LinkedHashMap<>();
        for (Models.MiddlewareCredential mc : profile.middleware) credentials.put(mc.component, mc.values);
        return new ProfileService.ResolvedProfile(profile, credentials);
    }

    private static Models.MiddlewareCredential mw(String component, Map<String, String> values) {
        Models.MiddlewareCredential mc = new Models.MiddlewareCredential();
        mc.component = component;
        mc.values.putAll(values);
        return mc;
    }

    private List<CatalogEntry> entries(ProfileService.ResolvedProfile resolved) {
        return resolved.profile().middleware.stream().map(mc -> catalog.entry(mc.component)).toList();
    }

    @Test
    void rendersIndependentCredentialsAndMixedApplicationVersions() {
        ProfileService.ResolvedProfile resolved = fourMiddlewareProfile();

        String middleware = renderer.middleware(resolved, entries(resolved), Map.of(
                "mysql", "8.4.11", "redis", "8.2.8", "rabbitmq", "4.3.4-management",
                "minio", "RELEASE.2025-07-18T21-56-31Z"), Models.BuildTarget.defaultTarget());
        String application = renderer.application(resolved, "kunlun-app", "1.1.1", "1.1.2",
                "/actuator/health", "/", entries(resolved), Set.of("app-backend", "app-frontend"), Models.BuildTarget.defaultTarget());

        assertThat(middleware).contains("x-kunlun-mysql-password", "mysql-password-123",
                "redis-password-123", "rabbit-password-123", "minio-password-123",
                "image: mysql:8.4.11", "platform: linux/amd64");
        assertThat(application).contains("image: kunlun-app-backend:1.1.1",
                "image: kunlun-app-frontend:1.1.2", "SPRING_DATA_REDIS_DATABASE: 2",
                "8088:80", "http://127.0.0.1:8080/actuator/health");
    }

    @Test
    void rendersArm64PlatformForKylinArmTarget() {
        ProfileService.ResolvedProfile resolved = fourMiddlewareProfile();
        Models.BuildTarget target = Models.BuildTarget.of("kylin-v10", "arm64");

        String middleware = renderer.middleware(resolved, entries(resolved), Map.of(
                "mysql", "8.4.11", "redis", "8.2.8", "rabbitmq", "4.3.4-management",
                "minio", "RELEASE.2025-07-18T21-56-31Z"), target);
        String application = renderer.application(resolved, "kunlun-app", "1.1.1", "1.1.2",
                "/actuator/health", "/", entries(resolved), Set.of("app-backend", "app-frontend"), target);

        assertThat(middleware).contains("platform: linux/arm64");
        assertThat(application).contains("platform: linux/arm64");
    }

    @Test
    void rendersRegisteredDomesticDatabase() {
        Models.DeploymentProfile profile = new Models.DeploymentProfile();
        profile.id = "arm-site";
        profile.revision = 1;
        profile.frontendPort = 80;
        profile.timezone = "Asia/Shanghai";
        profile.javaOptions = "-Xms128m -Xmx512m";
        profile.middleware = List.of(
                mw("mysql", Map.of("rootPassword", "root-password-123", "database", "kunlun_app",
                        "user", "app_user", "password", "mysql-password-123")),
                mw("kingbase", Map.of("database", "kunlun_app", "user", "system", "password", "king-password-123")),
                mw("minio", Map.of("user", "minioadmin", "secret", "minio-password-123", "bucket", "kunlun-app")));
        Map<String, Map<String, String>> credentials = new LinkedHashMap<>();
        for (Models.MiddlewareCredential mc : profile.middleware) credentials.put(mc.component, mc.values);
        ProfileService.ResolvedProfile resolved = new ProfileService.ResolvedProfile(profile, credentials);

        String middleware = renderer.middleware(resolved, entries(resolved), Map.of(
                "mysql", "8.4.11", "kingbase", "V8R6", "minio", "RELEASE.2025-07-18T21-56-31Z"),
                Models.BuildTarget.defaultTarget());
        String application = renderer.application(resolved, "kunlun-app", "1.1.1", "1.1.1",
                "/actuator/health", "/", entries(resolved), Set.of("app-backend", "app-frontend"), Models.BuildTarget.defaultTarget());

        assertThat(middleware).contains("kingbase:", "image: kingbase:V8R6",
                "x-kunlun-kingbase-password", "king-password-123");
        assertThat(application).contains("jdbc:kingbase8://kingbase:54321/kunlun_app");
    }
}