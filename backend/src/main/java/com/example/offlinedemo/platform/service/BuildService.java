package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.store.PlatformStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        Models.BuildTarget target = Models.BuildTarget.of(project.targetOs, project.targetArch).normalized();
        if (input.targetOs != null && !input.targetOs.isBlank() && !target.os.equals(input.targetOs))
            throw new IllegalArgumentException("构建目标系统必须使用项目创建时固定的 " + target.os);
        if (input.targetArch != null && !input.targetArch.isBlank() && !target.arch.equals(input.targetArch))
            throw new IllegalArgumentException("构建架构必须使用项目创建时固定的 " + target.arch);
        Models.BuildTarget profileTarget = Models.BuildTarget.of(profile.targetOs, profile.targetArch).normalized();
        if (!profileTarget.os.equals(target.os) || !profileTarget.arch.equals(target.arch))
            throw new IllegalArgumentException("部署配置架构 " + profileTarget.description()
                    + " 与项目架构 " + target.description() + " 不一致");
        String type = input.packageType == null ? "" : input.packageType.toUpperCase(Locale.ROOT);
        if (!List.of("BOOTSTRAP", "APP_UPDATE").contains(type)) throw new IllegalArgumentException("不支持的包类型");
        requireVersion(input.targetVersion, "目标版本");
        List<String> scope = normalizeScope(input.updateScope);
        if ("BOOTSTRAP".equals(type)) scope = List.of("BACKEND", "FRONTEND");
        else {
            requireVersion(input.fromVersion, "起始版本");
            if (input.fromVersion.equals(input.targetVersion)) throw new IllegalArgumentException("更新包的起始版本和目标版本不能相同");
        }
        List<String> dbInitSqlIds = input.dbInitSqlIds == null ? List.of() : List.copyOf(input.dbInitSqlIds);
        List<String> dbMigrationSqlIds = input.dbMigrationSqlIds == null ? List.of() : List.copyOf(input.dbMigrationSqlIds);
        if (input.dbMigrationRequired && dbMigrationSqlIds.isEmpty())
            throw new IllegalArgumentException("声明数据库迁移时必须选择迁移 SQL 脚本");
        for (String id : dbInitSqlIds) {
            Models.SqlScript script = store.sqlScript(id);
            if (!"INIT".equals(script.kind)) throw new IllegalArgumentException("脚本 " + script.name + " 不是初始化脚本");
        }
        for (String id : dbMigrationSqlIds) {
            Models.SqlScript script = store.sqlScript(id);
            if (!"MIGRATION".equals(script.kind)) throw new IllegalArgumentException("脚本 " + script.name + " 不是迁移脚本");
            if (!input.targetVersion.equals(script.targetVersion))
                throw new IllegalArgumentException("迁移脚本 " + script.name + " 目标版本 " + script.targetVersion
                        + " 与目标 " + input.targetVersion + " 不一致");
        }
        if (input.packageRevision != null && !input.packageRevision.isBlank()
                && !input.packageRevision.matches("^r[1-9][0-9]*$"))
            throw new IllegalArgumentException("包修订号必须使用 r1、r2 这类格式");

        List<String> artifactIds = input.artifactIds == null ? List.of() : List.copyOf(input.artifactIds);
        Set<String> components = new LinkedHashSet<>();
        Map<String, String> sourceCommits = new java.util.LinkedHashMap<>();
        for (String id : artifactIds) {
            Models.Artifact artifact = store.artifact(id);
            if (!target.ociPlatform().equals(artifact.architecture))
                throw new IllegalArgumentException("制品 " + artifact.component + " " + artifact.version
                        + " 架构为 " + artifact.architecture + "，与目标 " + target.ociPlatform() + " 不一致");
            if (ArtifactService.APP_IMAGE_COMPONENTS.contains(artifact.component)
                    && !input.targetVersion.equals(artifact.version))
                throw new IllegalArgumentException("应用镜像制品 " + artifact.component + " 版本 " + artifact.version
                        + " 必须与目标版本 " + input.targetVersion + " 一致（镜像名 <appKey>-<role>:<版本>）");
            if (ArtifactService.APP_IMAGE_COMPONENTS.contains(artifact.component)) {
                if (!project.id.equals(artifact.projectId))
                    throw new IllegalArgumentException("应用镜像制品 " + artifact.component + " 不属于当前项目");
                String expectedRole = artifact.component.substring("app-".length()).toUpperCase(Locale.ROOT);
                if (!expectedRole.equals(artifact.applicationRole))
                    throw new IllegalArgumentException("应用镜像制品角色与组件不一致：" + artifact.component);
                if (artifact.gitCommit == null || artifact.gitCommit.isBlank())
                    throw new IllegalArgumentException("应用镜像制品 " + artifact.component + " 未绑定 Git 提交");
                sourceCommits.put(artifact.applicationRole, artifact.gitCommit);
            }
            components.add(artifact.component);
        }
        if (components.size() != artifactIds.size()) throw new IllegalArgumentException("同一个组件只能选择一个制品版本");
        if ("BOOTSTRAP".equals(type)) {
            Set<String> required = new LinkedHashSet<>();
            required.add("docker-engine");
            required.add("docker-compose");
            required.add("app-backend");
            required.add("app-frontend");
            for (Models.MiddlewareCredential mc : profile.middleware) required.add(mc.component);
            for (String req : required) if (!components.contains(req))
                throw new IllegalArgumentException("初始化包必须选择前后端应用镜像、Docker、Compose 以及部署配置中声明的每个中间件制品");
            for (String c : components) if (!required.contains(c))
                throw new IllegalArgumentException("初始化包不支持该制品组件：" + c);
        } else {
            for (String role : scope) {
                String appComponent = "app-" + role.toLowerCase(Locale.ROOT);
                if (!components.contains(appComponent))
                    throw new IllegalArgumentException("更新范围包含 " + role + "，但未选择对应的 " + appComponent + " 应用镜像制品");
            }
            for (String component : components) if (!ArtifactService.APP_IMAGE_COMPONENTS.contains(component))
                throw new IllegalArgumentException("应用更新包只能选择前后端应用镜像制品");
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
        spec.dbInitSqlIds = new ArrayList<>(dbInitSqlIds);
        spec.dbMigrationSqlIds = new ArrayList<>(dbMigrationSqlIds);
        spec.targetOs = target.os;
        spec.targetArch = target.arch;

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
        task.sourceCommits.putAll(sourceCommits);
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
        public List<String> dbInitSqlIds;
        public List<String> dbMigrationSqlIds;
        public String targetOs;
        public String targetArch;
    }
}
