package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.config.PlatformProperties;
import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.store.PlatformStore;
import com.example.offlinedemo.platform.util.FileSupport;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class ArtifactService {
    public static final Set<String> COMPONENTS = Set.of(
            "docker-engine", "docker-compose", "mysql", "redis", "rabbitmq", "minio",
            "postgresql", "kafka", "elasticsearch");
    private final PlatformStore store;
    private final Path projectRoot;

    public ArtifactService(PlatformStore store, PlatformProperties properties) {
        this.store = store;
        this.projectRoot = properties.projectRootPath().toAbsolutePath().normalize();
    }

    public Models.Artifact importFile(String component, String version, String sourcePath) throws Exception {
        String normalizedComponent = component == null ? "" : component.toLowerCase(Locale.ROOT);
        if (!COMPONENTS.contains(normalizedComponent)) throw new IllegalArgumentException("不支持的制品组件：" + component);
        if (version == null || !version.matches("^[A-Za-z0-9][A-Za-z0-9._+-]{0,79}$"))
            throw new IllegalArgumentException("制品版本格式不正确");
        if (sourcePath == null || sourcePath.isBlank()) throw new IllegalArgumentException("源文件路径不能为空");
        Path source = Path.of(sourcePath);
        if (!source.isAbsolute()) source = projectRoot.resolve(source).normalize();
        if (!Files.isRegularFile(source)) throw new IllegalArgumentException("源文件不存在：" + source);
        String lowerName = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if ("docker-engine".equals(normalizedComponent) && !lowerName.endsWith(".tgz"))
            throw new IllegalArgumentException("Docker Engine 制品必须是 .tgz");
        if (!List.of("docker-engine", "docker-compose").contains(normalizedComponent) && !lowerName.endsWith(".tar"))
            throw new IllegalArgumentException("中间件镜像制品必须是 docker save 生成的 .tar");

        Models.Artifact artifact = new Models.Artifact();
        artifact.id = UUID.randomUUID().toString();
        artifact.component = normalizedComponent;
        artifact.version = version;
        artifact.architecture = Models.ARCHITECTURE;
        String safeName = source.getFileName().toString().replaceAll("[^A-Za-z0-9._+-]", "-");
        artifact.fileName = safeName;
        Path directory = store.artifactsRoot().resolve(normalizedComponent).resolve(version).normalize();
        if (!directory.startsWith(store.artifactsRoot())) throw new IllegalArgumentException("制品目录越界");
        Files.createDirectories(directory);
        Path destination = directory.resolve(artifact.id.substring(0, 8) + "-" + safeName);
        Files.copy(source, destination);
        artifact.storagePath = destination.toString();
        artifact.size = Files.size(destination);
        artifact.sha256 = FileSupport.sha256(destination);
        artifact.createdAt = Instant.now();
        store.putArtifact(artifact);
        return artifact;
    }

    public List<Models.Artifact> selected(List<String> ids) {
        return ids == null ? List.of() : ids.stream().map(store::artifact).toList();
    }
}
