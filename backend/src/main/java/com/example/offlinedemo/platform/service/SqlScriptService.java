package com.example.offlinedemo.platform.service;

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

/**
 * 数据库脚本制品入库。镜像 {@link ArtifactService} 的存储模式：通过页面上传 .sql 文件，
 * 经 {@link BlobStore} 落地（本地或 MinIO），计算 SHA256 后持久化，构建时按 id 引用。
 */
@Service
public class SqlScriptService {
    public static final String KIND_INIT = "INIT";
    public static final String KIND_MIGRATION = "MIGRATION";
    private static final List<String> KINDS = List.of(KIND_INIT, KIND_MIGRATION);

    private final PlatformStore store;
    private final BlobStore blobStore;

    public SqlScriptService(PlatformStore store, BlobStore blobStore, PlatformProperties properties) {
        this.store = store;
        this.blobStore = blobStore;
    }

    public Models.SqlScript importFile(String kind, String name, String targetVersion, String sourcePath) throws Exception {
        String normalizedKind = kind == null ? "" : kind.toUpperCase(Locale.ROOT);
        if (!KINDS.contains(normalizedKind)) throw new IllegalArgumentException("脚本类型必须是 INIT 或 MIGRATION");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("脚本名称不能为空");
        if (targetVersion == null || !targetVersion.matches("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$"))
            throw new IllegalArgumentException("目标版本必须使用语义化版本，例如 1.2.3");
        if (sourcePath == null || sourcePath.isBlank()) throw new IllegalArgumentException("源文件路径不能为空");
        Path source = Path.of(sourcePath);
        if (!Files.isRegularFile(source)) throw new IllegalArgumentException("源文件不存在：" + source);
        String lowerName = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".sql")) throw new IllegalArgumentException("数据库脚本必须是 .sql 文件");

        Models.SqlScript script = new Models.SqlScript();
        script.id = UUID.randomUUID().toString();
        script.kind = normalizedKind;
        script.name = name.trim();
        script.targetVersion = targetVersion.trim();
        String safeName = source.getFileName().toString().replaceAll("[^A-Za-z0-9._+-]", "-");
        script.fileName = safeName;
        script.size = Files.size(source);
        script.sha256 = FileSupport.sha256(source);

        String prefix = script.id.substring(0, 8) + "-" + safeName;
        String destination = blobStore.remote()
                ? "sql-scripts/" + normalizedKind + "/" + targetVersion + "/" + prefix
                : store.sqlScriptsRoot().resolve(normalizedKind).resolve(targetVersion).resolve(prefix).toString();
        BlobStore.BlobRef ref = blobStore.put(source, destination);
        script.storeType = ref.storeType();
        if ("minio".equals(ref.storeType())) script.objectKey = ref.ref();
        else script.storagePath = ref.ref();

        script.createdAt = Instant.now();
        store.putSqlScript(script);
        return script;
    }

    public List<Models.SqlScript> selected(List<String> ids) {
        return ids == null ? List.of() : ids.stream().map(store::sqlScript).toList();
    }
}
