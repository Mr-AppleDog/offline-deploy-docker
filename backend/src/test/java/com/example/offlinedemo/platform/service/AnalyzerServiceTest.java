package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.config.PlatformProperties;
import com.example.offlinedemo.platform.domain.Models;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzerServiceTest {
    @TempDir Path directory;

    @Test
    void detectsFrameworkAndMiddlewareWithEvidence() throws Exception {
        Files.writeString(directory.resolve("pom.xml"), """
                <dependency><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
                <dependency><artifactId>mysql-connector-j</artifactId></dependency>
                <dependency><artifactId>spring-boot-starter-amqp</artifactId></dependency>
                <dependency><groupId>io.minio</groupId></dependency>
                """);
        Files.writeString(directory.resolve("Dockerfile"), "FROM eclipse-temurin:17-jre");
        PlatformProperties properties = new PlatformProperties();
        AnalyzerService analyzer = new AnalyzerService(properties);
        Models.AnalysisResult result = analyzer.analyze(List.of(
                new RepositoryService.RepositorySnapshot("BACKEND", directory, directory, "abc123", "Dockerfile")));

        assertThat(result.commits).containsEntry("BACKEND", "abc123");
        assertThat(result.findings).extracting(finding -> finding.component)
                .contains("SPRING_BOOT", "mysql", "redis", "rabbitmq", "minio", "DOCKERFILE");
        assertThat(result.findings).allMatch(finding -> !finding.evidence.isEmpty() && finding.confirmed);
    }
}
