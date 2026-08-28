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
        return importFile(component, version, sourcePath, arch, new ImportMetadata());
    }

    public Models.Artifact importApplication(String projectId, String role, String version,
                                             String gitCommit, String sourcePath) throws Exception {
        Models.Project project = store.project(projectId);
        String normalizedRole = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!List.of("FRONTEND", "BACKEND").contains(normalizedRole))
            throw new IllegalArgumentException("应用角色只支持 FRONTEND 或 BACKEND");
        if (gitCommit == null || !gitCommit.matches("^[0-9a-fA-F]{7,64}$"))
            throw new IllegalArgumentException("Git 提交必须是 7-64 位十六进制 commit id");
        Models.RepositoryConfig repository = project.repositories.stream()
                .filter(value -> normalizedRole.equals(value.role)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请先为项目绑定 " + normalizedRole + " Git 仓库"));
        ImportMetadata metadata = new ImportMetadata();
        metadata.sourceType = "UPLOAD";
        metadata.projectId = project.id;
        metadata.projectName = project.name;
        metadata.applicationRole = normalizedRole;
        metadata.gitRepositoryId = repository.id;
        metadata.gitRepositoryUrl = repository.url;
        metadata.gitRef = repository.ref;
        metadata.gitCommit = gitCommit.toLowerCase(Locale.ROOT);
        metadata.imageReference = project.appKey + "-" + normalizedRole.toLowerCase(Locale.ROOT) + ":" + version;
        return importFile("app-" + normalizedRole.toLowerCase(Locale.ROOT), version, sourcePath,
                project.targetArch, metadata);
    }

    public Models.Artifact importFile(String component, String version, String sourcePath, String arch,
                                      ImportMetadata metadata) throws Exception {
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
        ImportMetadata details = metadata == null ? new ImportMetadata() : metadata;
        artifact.sourceType = blankDefault(details.sourceType, "UPLOAD");
        artifact.projectId = clean(details.projectId);
        artifact.projectName = clean(details.projectName);
        artifact.applicationRole = clean(details.applicationRole);
        artifact.gitRepositoryId = clean(details.gitRepositoryId);
        artifact.gitRepositoryUrl = clean(details.gitRepositoryUrl);
        artifact.gitRef = clean(details.gitRef);
        artifact.gitCommit = clean(details.gitCommit);
        artifact.imageRegistryId = clean(details.imageRegistryId);
        artifact.imageReference = clean(details.imageReference);
        artifact.imageId = clean(details.imageId);
        artifact.imageDigest = clean(details.imageDigest);

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

    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String blankDefault(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }

    public static final class ImportMetadata {
        public String sourceType;
        public String projectId;
        public String projectName;
        public String applicationRole;
        public String gitRepositoryId;
        public String gitRepositoryUrl;
        public String gitRef;
        public String gitCommit;
        public String imageRegistryId;
        public String imageReference;
        public String imageId;
        public String imageDigest;
    }

    public List<Models.Artifact> selected(List<String> ids) {
        return ids == null ? List.of() : ids.stream().map(store::artifact).toList();
    }
}
