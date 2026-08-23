package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.config.PlatformProperties;
import com.example.offlinedemo.platform.domain.Models;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class AnalyzerService {
    private static final Set<String> INTERESTING = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "package.json", "application.yml",
            "application.yaml", "application.properties", "dockerfile", "docker-compose.yml",
            "docker-compose.yaml", "compose.yml", "compose.yaml");
    private final int maxFiles;

    public AnalyzerService(PlatformProperties properties) {
        maxFiles = Math.max(100, properties.getMaxAnalysisFiles());
    }

    public Models.AnalysisResult analyze(List<RepositoryService.RepositorySnapshot> snapshots) throws IOException {
        Map<String, Models.Finding> findings = new LinkedHashMap<>();
        Models.AnalysisResult result = new Models.AnalysisResult();
        result.analyzedAt = Instant.now();
        int scanned = 0;
        for (RepositoryService.RepositorySnapshot snapshot : snapshots) {
            result.commits.put(snapshot.role(), snapshot.commit());
            try (Stream<Path> walk = Files.walk(snapshot.contextRoot())) {
                for (Path file : walk.filter(path -> {
                            String relative = snapshot.contextRoot().relativize(path).toString().replace('\\', '/');
                            return !relative.equals(".git") && !relative.startsWith(".git/")
                                    && !relative.contains("/node_modules/") && !relative.contains("/target/")
                                    && !relative.contains("/dist/");
                        }).filter(Files::isRegularFile).limit(maxFiles).toList()) {
                    String relative = snapshot.contextRoot().relativize(file).toString().replace('\\', '/');
                    scanned++;
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!INTERESTING.contains(name) && !name.endsWith(".sql")) continue;
                    if (Files.size(file) > 2 * 1024 * 1024) continue;
                    String content;
                    try { content = Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT); }
                    catch (Exception ignored) { continue; }
                    String evidence = snapshot.role() + ":" + relative;
                    detect(findings, content, name, evidence);
                }
            }
        }
        result.scannedFiles = scanned;
        result.findings.addAll(findings.values());
        return result;
    }

    private void detect(Map<String, Models.Finding> values, String content, String name, String evidence) {
        if (content.contains("spring-boot") || content.contains("org.springframework.boot"))
            add(values, "SPRING_BOOT", "FRAMEWORK", "Spring Boot", .99, evidence);
        if (content.contains("\"vue\"") || content.contains("@vitejs/plugin-vue"))
            add(values, "VUE", "FRAMEWORK", "Vue", .99, evidence);
        if (content.contains("\"react\"") || content.contains("react-dom"))
            add(values, "REACT", "FRAMEWORK", "React", .98, evidence);
        if (content.contains("mysql-connector") || content.contains("jdbc:mysql") || content.contains("image: mysql"))
            add(values, "MYSQL", "MIDDLEWARE", "MySQL", .99, evidence);
        if (content.contains("data-redis") || content.contains("redis://") || content.contains("image: redis"))
            add(values, "REDIS", "MIDDLEWARE", "Redis", .98, evidence);
        if (content.contains("spring-boot-starter-amqp") || content.contains("rabbitmq") || content.contains("amqp://"))
            add(values, "RABBITMQ", "MIDDLEWARE", "RabbitMQ", .98, evidence);
        if (content.contains("io.minio") || content.contains("minio/minio") || content.contains("minio_endpoint"))
            add(values, "MINIO", "MIDDLEWARE", "MinIO", .98, evidence);
        if (content.contains("spring-kafka") || content.contains("kafka-clients") || content.contains("image: kafka"))
            add(values, "KAFKA", "MIDDLEWARE", "Kafka", .97, evidence);
        if (content.contains("elasticsearch") || content.contains("spring-data-elasticsearch"))
            add(values, "ELASTICSEARCH", "MIDDLEWARE", "Elasticsearch", .94, evidence);
        if (content.contains("jdbc:postgresql") || content.contains("postgresql") || content.contains("image: postgres"))
            add(values, "POSTGRESQL", "MIDDLEWARE", "PostgreSQL", .96, evidence);
        if (name.endsWith(".sql")) add(values, "DATABASE_SCRIPTS", "DATABASE", "数据库脚本", .90, evidence);
        if ("dockerfile".equals(name)) add(values, "DOCKERFILE", "BUILD", "Dockerfile", 1, evidence);
    }

    private void add(Map<String, Models.Finding> values, String component, String category,
                     String label, double confidence, String evidence) {
        Models.Finding finding = values.computeIfAbsent(component, ignored -> {
            Models.Finding created = new Models.Finding();
            created.component = component;
            created.category = category;
            created.label = label;
            created.confidence = confidence;
            created.confirmed = true;
            return created;
        });
        if (finding.evidence.size() < 8 && !finding.evidence.contains(evidence)) finding.evidence.add(evidence);
    }
}
