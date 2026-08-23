package com.example.offlinedemo.platform.store;

import com.example.offlinedemo.platform.config.PlatformProperties;
import com.example.offlinedemo.platform.domain.Models;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 平台聚合根仓库。内存中维护状态，持久化下沉到 {@link MetadataStore}
 * （MySQL 或本地 JSON，由 PersistenceConfig 决定）。
 */
@Component
public class PlatformStore {
    private final MetadataStore metadataStore;
    private final Path root;
    private Models.PlatformState state = new Models.PlatformState();

    public PlatformStore(MetadataStore metadataStore, PlatformProperties properties) {
        this.metadataStore = metadataStore;
        this.root = properties.dataDirPath().toAbsolutePath().normalize();
    }

    @PostConstruct
    public synchronized void initialize() throws Exception {
        Files.createDirectories(root);
        Files.createDirectories(artifactsRoot());
        Files.createDirectories(workspacesRoot());
        Files.createDirectories(deliveriesRoot());
        Files.createDirectories(logsRoot());
        state = metadataStore.load();
        if (state.projects == null) state.projects = new java.util.LinkedHashMap<>();
        if (state.profiles == null) state.profiles = new java.util.LinkedHashMap<>();
        if (state.artifacts == null) state.artifacts = new java.util.LinkedHashMap<>();
        if (state.builds == null) state.builds = new java.util.LinkedHashMap<>();
        boolean recovered = false;
        for (Models.BuildTask task : state.builds.values()) {
            if ("QUEUED".equals(task.status) || "RUNNING".equals(task.status)) {
                task.status = "FAILED";
                task.stage = "INTERRUPTED";
                task.error = "平台重启导致构建中断，请重新发起任务。";
                task.finishedAt = Instant.now();
                recovered = true;
            }
        }
        if (recovered) save();
    }

    public Path root() { return root; }
    public Path artifactsRoot() { return root.resolve("artifacts"); }
    public Path workspacesRoot() { return root.resolve("workspaces"); }
    public Path deliveriesRoot() { return root.resolve("deliveries"); }
    public Path logsRoot() { return root.resolve("logs"); }

    public boolean remote() { return metadataStore.remote(); }

    public synchronized List<Models.Project> projects() {
        return state.projects.values().stream()
                .sorted(Comparator.comparing((Models.Project p) -> p.updatedAt).reversed())
                .toList();
    }

    public synchronized Models.Project project(String id) {
        Models.Project value = state.projects.get(id);
        if (value == null) throw new NotFoundException("项目不存在：" + id);
        return value;
    }

    public synchronized void putProject(Models.Project project) {
        state.projects.put(project.id, project);
        save();
    }

    public synchronized void deleteProject(String id) {
        if (state.projects.remove(id) == null) throw new NotFoundException("项目不存在：" + id);
        save();
    }

    public synchronized List<Models.DeploymentProfile> profiles() {
        return state.profiles.values().stream()
                .sorted(Comparator.comparing((Models.DeploymentProfile p) -> p.updatedAt).reversed())
                .toList();
    }

    public synchronized Models.DeploymentProfile profile(String id) {
        Models.DeploymentProfile value = state.profiles.get(id);
        if (value == null) throw new NotFoundException("部署配置不存在：" + id);
        return value;
    }

    public synchronized void putProfile(Models.DeploymentProfile profile) {
        state.profiles.put(profile.id, profile);
        save();
    }

    public synchronized List<Models.Artifact> artifacts() {
        return state.artifacts.values().stream()
                .sorted(Comparator.comparing((Models.Artifact a) -> a.createdAt).reversed())
                .toList();
    }

    public synchronized Models.Artifact artifact(String id) {
        Models.Artifact value = state.artifacts.get(id);
        if (value == null) throw new NotFoundException("离线制品不存在：" + id);
        return value;
    }

    public synchronized void putArtifact(Models.Artifact artifact) {
        state.artifacts.put(artifact.id, artifact);
        save();
    }

    public synchronized List<Models.BuildTask> builds() {
        return state.builds.values().stream()
                .sorted(Comparator.comparing((Models.BuildTask b) -> b.createdAt).reversed())
                .toList();
    }

    public synchronized Models.BuildTask build(String id) {
        Models.BuildTask value = state.builds.get(id);
        if (value == null) throw new NotFoundException("构建任务不存在：" + id);
        return value;
    }

    public synchronized void putBuild(Models.BuildTask build) {
        state.builds.put(build.id, build);
        save();
    }

    public synchronized void updateBuild(String id, Consumer<Models.BuildTask> mutation) {
        Models.BuildTask task = build(id);
        mutation.accept(task);
        save();
    }

    public synchronized long countBuildsByStatus(String status) {
        return state.builds.values().stream().filter(b -> status.equals(b.status)).count();
    }

    private void save() {
        try {
            metadataStore.save(state);
        } catch (Exception e) {
            throw new IllegalStateException("保存平台状态失败", e);
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }
}