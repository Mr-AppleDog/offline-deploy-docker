package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.catalog.MiddlewareCatalog;
import com.example.offlinedemo.platform.config.PlatformProperties;
import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.store.BlobStore;
import com.example.offlinedemo.platform.store.PlatformStore;
import com.example.offlinedemo.platform.util.FileSupport;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ArtifactService {
    public static final List<String> INFRA_COMPONENTS = List.of("docker-engine", "docker-compose");
    /** 应用镜像制品：由用户在平台外构建并 save 成 tar 导入，平台不再从源码构建。 */
    public static final List<String> APP_IMAGE_COMPONENTS = List.of("app-backend", "app-frontend");
    private final PlatformStore store;
    private final MiddlewareCatalog catalog;
    private final BlobStore blobStore;
    private final Path projectRoot;

    public ArtifactService(PlatformStore store, MiddlewareCatalog catalog, BlobStore blobStore,
                           PlatformProperties properties) {
        this.store = store;
        this.catalog = catalog;
        this.blobStore = blobStore;
        this.projectRoot = properties.projectRootPath().toAbsolutePath().normalize();
    }

    public Models.Artifact importFile(String component, String version, String sourcePath, String arch) throws Exception {
        String normalizedComponent = component == null ? "" : component.toLowerCase(Locale.ROOT);
        if (!INFRA_COMPONENTS.contains(normalizedComponent)
                && !APP_IMAGE_COMPONENTS.contains(normalizedComponent)
                && !catalog.exists(normalizedComponent))
            throw new IllegalArgumentException("不支持的制品组件：" + component);
        if (version == null || !version.matches("^[A-Za-z0-9][A-Za-z0-9._+-]{0,79}$"))
            throw new IllegalArgumentException("制品版本格式不正确");
        if (sourcePath == null || sourcePath.isBlank()) throw new IllegalArgumentException("源文件路径不能为空");
        Models.BuildTarget target = Models.BuildTarget.of(null, arch).normalized();
        Path source = Path.of(sourcePath);
        if (!source.isAbsolute()) source = projectRoot.resolve(source).normalize();
        if (!Files.isRegularFile(source)) throw new IllegalArgumentException("源文件不存在：" + source);
        String lowerName = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if ("docker-engine".equals(normalizedComponent) && !lowerName.endsWith(".tgz"))
            throw new IllegalArgumentException("Docker Engine 制品必须是 .tgz");
        if (!INFRA_COMPONENTS.contains(normalizedComponent) && !lowerName.endsWith(".tar"))
            throw new IllegalArgumentException("镜像制品必须是 docker save 生成的 .tar");

        Models.Artifact artifact = new Models.Artifact();
        artifact.id = UUID.randomUUID().toString();
        artifact.component = normalizedComponent;
        artifact.version = version;
        artifact.architecture = target.ociPlatform();
        String safeName = source.getFileName().toString().replaceAll("[^A-Za-z0-9._+-]", "-");
        artifact.fileName = safeName;
        artifact.size = Files.size(source);
        artifact.sha256 = FileSupport.sha256(source);

        String prefix = artifact.id.substring(0, 8) + "-" + safeName;
        String destination = blobStore.remote()
                ? "artifacts/" + normalizedComponent + "/" + version + "/" + prefix
                : store.artifactsRoot().resolve(normalizedComponent).resolve(version).resolve(prefix).toString();
        BlobStore.BlobRef ref = blobStore.put(source, destination);
        artifact.storeType = ref.storeType();
        if ("minio".equals(ref.storeType())) artifact.objectKey = ref.ref();
        else artifact.storagePath = ref.ref();

        artifact.createdAt = Instant.now();
        store.putArtifact(artifact);
        return artifact;
    }

    public List<Models.Artifact> selected(List<String> ids) {
        return ids == null ? List.of() : ids.stream().map(store::artifact).toList();
    }
}