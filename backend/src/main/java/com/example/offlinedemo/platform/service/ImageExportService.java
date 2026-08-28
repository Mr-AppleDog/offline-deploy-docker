package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.catalog.CatalogEntry;
import com.example.offlinedemo.platform.catalog.MiddlewareCatalog;
import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.store.PlatformStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

@Service
public class ImageExportService {
    private final PlatformStore store;
    private final MiddlewareCatalog catalog;
    private final ExecutorService executor;
    private final ImageExportWorker worker;

    public ImageExportService(PlatformStore store, MiddlewareCatalog catalog, ExecutorService executor,
                              ImageExportWorker worker) {
        this.store = store;
        this.catalog = catalog;
        this.executor = executor;
        this.worker = worker;
    }

    public Models.ImageExportTask create(ImageExportInput input) {
        if ((input.projectId != null && !input.projectId.isBlank())
                || (input.applicationRole != null && !input.applicationRole.isBlank())
                || (input.registryId != null && !input.registryId.isBlank()))
            return createApplication(input);
        return createMiddleware(input);
    }

    private Models.ImageExportTask createMiddleware(ImageExportInput input) {
        String component = input.component == null ? "" : input.component.trim().toLowerCase(Locale.ROOT);
        CatalogEntry entry = catalog.entry(component);
        if (input.version == null || !input.version.matches("^[A-Za-z0-9][A-Za-z0-9._+-]{0,79}$"))
            throw new IllegalArgumentException("镜像版本格式不正确");
        Models.BuildTarget target = Models.BuildTarget.of(input.targetOs, input.targetArch).normalized();
        if (!entry.architectures.contains(target.arch))
            throw new IllegalArgumentException(entry.displayName + " 不支持 " + target.arch + " 架构");
        String imageReference = entry.imageRepo + ":" + input.version;

        Models.ImageExportTask task = new Models.ImageExportTask();
        task.id = UUID.randomUUID().toString();
        task.component = component;
        task.version = input.version;
        task.imageReference = imageReference;
        task.targetOs = target.os;
        task.targetArch = target.arch;
        task.createdAt = Instant.now();

        Models.Artifact existing = store.artifacts().stream()
                .filter(value -> component.equals(value.component)
                        && input.version.equals(value.version)
                        && target.ociPlatform().equals(value.architecture))
                .findFirst().orElse(null);
        if (existing != null) {
            task.status = "SUCCEEDED";
            task.stage = "复用已有制品";
            task.progress = 100;
            task.artifactId = existing.id;
            task.reused = true;
            task.imageId = existing.imageId;
            task.imageDigest = existing.imageDigest;
            task.startedAt = task.createdAt;
            task.finishedAt = task.createdAt;
            store.putImageExportTask(task);
            return task;
        }

        boolean running = store.imageExportTasks().stream().anyMatch(value ->
                component.equals(value.component) && input.version.equals(value.version)
                        && target.arch.equals(value.targetArch)
                        && ("QUEUED".equals(value.status) || "RUNNING".equals(value.status)));
        if (running) throw new IllegalArgumentException("相同组件、版本和架构的镜像导出任务已在执行");

        task.status = "QUEUED";
        task.stage = "等待镜像导出 Worker";
        task.progress = 0;
        store.putImageExportTask(task);
        executor.execute(() -> worker.run(task.id));
        return task;
    }

    private Models.ImageExportTask createApplication(ImageExportInput input) {
        Models.Project project = store.project(input.projectId);
        String role = input.applicationRole == null ? "" : input.applicationRole.trim().toUpperCase(Locale.ROOT);
        if (!List.of("FRONTEND", "BACKEND").contains(role))
            throw new IllegalArgumentException("应用角色只支持 FRONTEND 或 BACKEND");
        if (input.registryId == null || input.registryId.isBlank())
            throw new IllegalArgumentException("请选择已绑定的镜像仓库");
        Models.ImageRegistryConfig registry = project.imageRegistries.stream()
                .filter(value -> input.registryId.equals(value.id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("项目镜像仓库绑定不存在"));
        if (!role.equals(registry.role)) throw new IllegalArgumentException("镜像仓库角色与应用角色不一致");
        Models.RepositoryConfig repository = project.repositories.stream()
                .filter(value -> role.equals(value.role)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请先为项目绑定 " + role + " Git 仓库"));
        if (input.gitCommit == null || !input.gitCommit.matches("^[0-9a-fA-F]{7,64}$"))
            throw new IllegalArgumentException("Git 提交必须是 7-64 位十六进制 commit id");
        if (input.tag == null || !input.tag.matches("^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$"))
            throw new IllegalArgumentException("镜像标签格式不正确");
        if (input.version == null || !input.version.matches("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$"))
            throw new IllegalArgumentException("应用版本必须使用语义化版本，例如 1.2.3");

        Models.BuildTarget target = Models.BuildTarget.of(project.targetOs, project.targetArch).normalized();
        if (input.targetOs != null && !input.targetOs.isBlank() && !target.os.equals(input.targetOs))
            throw new IllegalArgumentException("应用镜像目标系统必须使用项目固定的 " + target.os);
        if (input.targetArch != null && !input.targetArch.isBlank() && !target.arch.equals(input.targetArch))
            throw new IllegalArgumentException("应用镜像架构必须使用项目固定的 " + target.arch);

        String component = "app-" + role.toLowerCase(Locale.ROOT);
        String imageReference = ApplicationRegistryService.imageReference(registry, input.tag);
        String commit = input.gitCommit.toLowerCase(Locale.ROOT);
        Models.ImageExportTask task = new Models.ImageExportTask();
        task.id = UUID.randomUUID().toString();
        task.component = component;
        task.version = input.version;
        task.imageReference = imageReference;
        task.projectId = project.id;
        task.projectName = project.name;
        task.applicationRole = role;
        task.gitRepositoryId = repository.id;
        task.gitRepositoryUrl = repository.url;
        task.gitRef = repository.ref;
        task.gitCommit = commit;
        task.imageRegistryId = registry.id;
        task.targetOs = target.os;
        task.targetArch = target.arch;
        task.createdAt = Instant.now();

        Models.Artifact existing = store.artifacts().stream()
                .filter(value -> component.equals(value.component)
                        && input.version.equals(value.version)
                        && target.ociPlatform().equals(value.architecture)
                        && project.id.equals(value.projectId)
                        && imageReference.equals(value.imageReference)
                        && commit.equals(value.gitCommit))
                .findFirst().orElse(null);
        if (existing != null) {
            task.status = "SUCCEEDED";
            task.stage = "复用已有应用镜像制品";
            task.progress = 100;
            task.artifactId = existing.id;
            task.reused = true;
            task.imageId = existing.imageId;
            task.imageDigest = existing.imageDigest;
            task.startedAt = task.createdAt;
            task.finishedAt = task.createdAt;
            store.putImageExportTask(task);
            return task;
        }

        boolean running = store.imageExportTasks().stream().anyMatch(value ->
                imageReference.equals(value.imageReference) && target.arch.equals(value.targetArch)
                        && ("QUEUED".equals(value.status) || "RUNNING".equals(value.status)));
        if (running) throw new IllegalArgumentException("相同应用镜像和架构的导出任务已在执行");

        task.status = "QUEUED";
        task.stage = "等待应用镜像导出 Worker";
        task.progress = 0;
        store.putImageExportTask(task);
        executor.execute(() -> worker.run(task.id));
        return task;
    }

    public static final class ImageExportInput {
        public String component;
        public String version;
        public String projectId;
        public String applicationRole;
        public String registryId;
        public String tag;
        public String gitCommit;
        public String targetOs;
        public String targetArch;
    }
}
