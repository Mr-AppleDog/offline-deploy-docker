package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.config.PlatformProperties;
import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.security.CryptoService;
import com.example.offlinedemo.platform.store.PlatformStore;
import com.example.offlinedemo.platform.util.CommandRunner;
import com.example.offlinedemo.platform.util.FileSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ImageExportWorker {
    private final PlatformStore store;
    private final ArtifactService artifacts;
    private final CommandRunner commands;
    private final CryptoService crypto;
    private final ObjectMapper objectMapper;
    private final Path projectRoot;

    public ImageExportWorker(PlatformStore store, ArtifactService artifacts, CommandRunner commands,
                             CryptoService crypto, ObjectMapper objectMapper, PlatformProperties properties) {
        this.store = store;
        this.artifacts = artifacts;
        this.commands = commands;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
        this.projectRoot = properties.projectRootPath().toAbsolutePath().normalize();
    }

    public void run(String taskId) {
        Path workspace = null;
        try {
            Models.ImageExportTask task = store.imageExportTask(taskId);
            Models.BuildTarget target = Models.BuildTarget.of(task.targetOs, task.targetArch).normalized();
            workspace = FileSupport.safeResolve(store.workspacesRoot(), "image-export/" + taskId, "镜像导出目录");
            Files.createDirectories(workspace);
            Map<String, String> dockerEnvironment = dockerEnvironment(task, workspace);
            store.updateImageExportTask(taskId, value -> {
                value.status = "RUNNING";
                value.stage = "检查 Docker 环境";
                value.progress = 5;
                value.startedAt = Instant.now();
            });
            log(taskId, "开始制作 " + task.imageReference + "，目标平台 " + target.ociPlatform());
            commands.run(List.of("docker", "info"), projectRoot, dockerEnvironment, line -> log(taskId, line));

            if (task.imageRegistryId != null && !task.imageRegistryId.isBlank()) {
                Models.ImageRegistryConfig registry = registry(task);
                if ("BASIC".equals(registry.authType)) {
                    stage(taskId, "登录应用镜像仓库", 12);
                    commands.run(List.of("docker", "login", "--username", registry.username,
                                    "--password-stdin", ApplicationRegistryService.loginServer(registry)),
                            projectRoot, dockerEnvironment, line -> log(taskId, line),
                            crypto.decrypt(registry.secretCipher) + System.lineSeparator());
                }
            }

            stage(taskId, "拉取目标架构镜像", 20);
            commands.run(List.of("docker", "pull", "--platform", target.ociPlatform(), task.imageReference),
                    projectRoot, dockerEnvironment, line -> log(taskId, line));

            stage(taskId, "校验镜像架构", 55);
            String identity = commands.run(List.of("docker", "image", "inspect", "--format",
                    "{{.Id}}|{{.Os}}/{{.Architecture}}", task.imageReference), projectRoot, dockerEnvironment,
                    line -> log(taskId, line)).output().trim();
            String[] parts = identity.split("\\|", 2);
            if (parts.length != 2 || !target.ociPlatform().equals(parts[1]))
                throw new IllegalStateException("镜像架构不是 " + target.ociPlatform() + "：" + identity);
            String digestJson = commands.run(List.of("docker", "image", "inspect", "--format",
                    "{{json .RepoDigests}}", task.imageReference), projectRoot, dockerEnvironment, ignored -> {}).output().trim();
            List<String> digests = objectMapper.readValue(digestJson, new TypeReference<>() {});
            String digest = digests == null || digests.isEmpty() ? null : digests.get(0);

            stage(taskId, "导出 docker save TAR", 70);
            String safeVersion = task.version.replaceAll("[^A-Za-z0-9._+-]", "-");
            Path output = workspace.resolve(task.component + "-" + safeVersion + "-" + target.ociPlatform().replace('/', '-') + ".tar");
            commands.run(List.of("docker", "save", "--output", output.toString(), task.imageReference),
                    projectRoot, dockerEnvironment, line -> log(taskId, line));

            stage(taskId, "写入制品库", 90);
            ArtifactService.ImportMetadata metadata = new ArtifactService.ImportMetadata();
            metadata.sourceType = "REGISTRY_EXPORT";
            metadata.projectId = task.projectId;
            metadata.projectName = task.projectName;
            metadata.applicationRole = task.applicationRole;
            metadata.gitRepositoryId = task.gitRepositoryId;
            metadata.gitRepositoryUrl = task.gitRepositoryUrl;
            metadata.gitRef = task.gitRef;
            metadata.gitCommit = task.gitCommit;
            metadata.imageRegistryId = task.imageRegistryId;
            metadata.imageReference = task.imageReference;
            metadata.imageId = parts[0];
            metadata.imageDigest = digest;
            Models.Artifact artifact = artifacts.importFile(task.component, task.version, output.toString(),
                    target.arch, metadata);

            store.updateImageExportTask(taskId, value -> {
                value.status = "SUCCEEDED";
                value.stage = "镜像 TAR 已入库";
                value.progress = 100;
                value.artifactId = artifact.id;
                value.imageId = parts[0];
                value.imageDigest = digest;
                value.finishedAt = Instant.now();
            });
            log(taskId, "制作成功，制品 ID：" + artifact.id + "。本地 Docker 镜像缓存已保留。 ");
        } catch (Exception e) {
            String message = rootMessage(e);
            log(taskId, "制作失败：" + message);
            store.updateImageExportTask(taskId, value -> {
                value.status = "FAILED";
                value.stage = "制作失败";
                value.error = message;
                value.finishedAt = Instant.now();
            });
        } finally {
            if (workspace != null) {
                try {
                    FileSupport.deleteTree(store.workspacesRoot(), workspace);
                } catch (IOException cleanupFailure) {
                    log(taskId, "临时工作区清理失败：" + cleanupFailure.getMessage());
                }
            }
        }
    }

    private Map<String, String> dockerEnvironment(Models.ImageExportTask task, Path workspace) throws IOException {
        if (task.imageRegistryId == null || task.imageRegistryId.isBlank()) return Map.of();
        Path dockerConfig = workspace.resolve("docker-config");
        Files.createDirectories(dockerConfig);
        Map<String, String> environment = new HashMap<>();
        environment.put("DOCKER_CONFIG", dockerConfig.toString());
        return environment;
    }

    private Models.ImageRegistryConfig registry(Models.ImageExportTask task) {
        Models.Project project = store.project(task.projectId);
        return project.imageRegistries.stream().filter(value -> task.imageRegistryId.equals(value.id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("应用镜像仓库绑定已被删除"));
    }

    private void stage(String taskId, String stage, int progress) {
        store.updateImageExportTask(taskId, value -> { value.stage = stage; value.progress = progress; });
        log(taskId, stage);
    }

    private void log(String taskId, String message) {
        try {
            Path file = store.logsRoot().resolve("image-export-" + taskId + ".log");
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write("[" + Instant.now() + "] " + message);
                writer.newLine();
            }
        } catch (IOException ignored) {}
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
