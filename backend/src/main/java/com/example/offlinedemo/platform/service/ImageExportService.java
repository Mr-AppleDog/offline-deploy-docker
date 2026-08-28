package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.catalog.CatalogEntry;
import com.example.offlinedemo.platform.catalog.MiddlewareCatalog;
import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.store.PlatformStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
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

    public static final class ImageExportInput {
        public String component;
        public String version;
        public String targetOs;
        public String targetArch;
    }
}
