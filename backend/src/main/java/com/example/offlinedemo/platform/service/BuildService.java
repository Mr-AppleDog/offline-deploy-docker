package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.store.PlatformStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

@Service
public class BuildService {
    private final PlatformStore store;
    private final ExecutorService executor;
    private final BuildWorker worker;

    public BuildService(PlatformStore store, ExecutorService executor, BuildWorker worker) {
        this.store = store;
        this.executor = executor;
        this.worker = worker;
    }

    public Models.BuildTask create(BuildInput input) {
        Models.Project project = store.project(input.projectId);
        Models.DeploymentProfile profile = store.profile(input.profileId);
        if (project.analysis == null) throw new IllegalArgumentException("创建构建任务前必须完成仓库分析");
        String type = input.packageType == null ? "" : input.packageType.toUpperCase(Locale.ROOT);
        if (!List.of("BOOTSTRAP", "APP_UPDATE").contains(type)) throw new IllegalArgumentException("不支持的包类型");
        requireVersion(input.targetVersion, "目标版本");
        List<String> scope = normalizeScope(input.updateScope);
        if ("BOOTSTRAP".equals(type)) scope = List.of("BACKEND", "FRONTEND");
        else {
            requireVersion(input.fromVersion, "起始版本");
            if (input.fromVersion.equals(input.targetVersion)) throw new IllegalArgumentException("更新包的起始版本和目标版本不能相同");
        }
        for (String role : scope) {
            if (project.repositories.stream().noneMatch(repository -> role.equals(repository.role)))
                throw new IllegalArgumentException("项目没有配置 " + role + " 仓库");
        }
        if (input.dbMigrationRequired && (input.databaseMigrationDirectory == null || input.databaseMigrationDirectory.isBlank()))
            throw new IllegalArgumentException("声明数据库迁移时必须配置迁移目录");
        if (input.packageRevision != null && !input.packageRevision.isBlank()
                && !input.packageRevision.matches("^r[1-9][0-9]*$"))
            throw new IllegalArgumentException("包修订号必须使用 r1、r2 这类格式");

        List<String> artifactIds = input.artifactIds == null ? List.of() : List.copyOf(input.artifactIds);
        if ("BOOTSTRAP".equals(type)) {
            Set<String> components = new LinkedHashSet<>();
            for (String id : artifactIds) components.add(store.artifact(id).component);
            Set<String> required = Set.of("docker-engine", "docker-compose", "mysql", "redis", "rabbitmq", "minio");
            if (!components.equals(required)) throw new IllegalArgumentException("初始化包必须且只能选择 Docker、Compose 和四个中间件制品");
            if (components.size() != artifactIds.size()) throw new IllegalArgumentException("同一个组件只能选择一个制品版本");
        }

        Models.BuildSpec spec = new Models.BuildSpec();
        spec.projectId = project.id;
        spec.profileId = profile.id;
        spec.profileRevision = profile.revision;
        spec.packageType = type;
        spec.fromVersion = input.fromVersion;
        spec.targetVersion = input.targetVersion;
        spec.packageRevision = clean(input.packageRevision);
        spec.artifactIds = new ArrayList<>(artifactIds);
        spec.updateScope = new ArrayList<>(scope);
        spec.dbMigrationRequired = input.dbMigrationRequired;
        spec.databaseInitDirectory = clean(input.databaseInitDirectory);
        spec.databaseMigrationDirectory = clean(input.databaseMigrationDirectory);

        Models.BuildTask task = new Models.BuildTask();
        task.id = UUID.randomUUID().toString();
        task.projectId = project.id;
        task.projectName = project.name;
        task.profileId = profile.id;
        task.profileName = profile.name + " r" + profile.revision;
        task.packageType = type;
        task.fromVersion = spec.fromVersion;
        task.targetVersion = spec.targetVersion;
        task.status = "QUEUED";
        task.stage = "等待构建 Worker";
        task.progress = 0;
        task.spec = spec;
        task.createdAt = Instant.now();
        store.putBuild(task);
        executor.execute(() -> worker.run(task.id));
        return task;
    }

    private List<String> normalizeScope(List<String> values) {
        if (values == null || values.isEmpty()) return List.of("BACKEND", "FRONTEND");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String role = value.toUpperCase(Locale.ROOT);
            if (!List.of("BACKEND", "FRONTEND").contains(role)) throw new IllegalArgumentException("更新范围无效：" + value);
            result.add(role);
        }
        return List.copyOf(result);
    }

    private void requireVersion(String value, String label) {
        if (value == null || !value.matches("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$"))
            throw new IllegalArgumentException(label + "必须使用语义化版本，例如 1.2.3");
    }
    private String clean(String value) { return value == null ? "" : value.trim(); }

    public static final class BuildInput {
        public String projectId;
        public String profileId;
        public String packageType;
        public String fromVersion;
        public String targetVersion;
        public String packageRevision;
        public List<String> artifactIds;
        public List<String> updateScope;
        public boolean dbMigrationRequired;
        public String databaseInitDirectory;
        public String databaseMigrationDirectory;
    }
}
