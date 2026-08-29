package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.security.CryptoService;
import com.example.offlinedemo.platform.store.PlatformStore;
import com.example.offlinedemo.platform.util.FileSupport;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectService {
    private final PlatformStore store;
    private final CryptoService crypto;
    private final RepositoryService repositories;
    private final AnalyzerService analyzer;

    public ProjectService(PlatformStore store, CryptoService crypto, RepositoryService repositories,
                          AnalyzerService analyzer) {
        this.store = store;
        this.crypto = crypto;
        this.repositories = repositories;
        this.analyzer = analyzer;
    }

    public Models.Project save(String id, String name, String appKey, String description, String currentVersion,
                               String targetOs, String targetArch,
                               String backendHealthPath, String frontendHealthPath) {
        require(name, "项目名称");
        if (appKey == null || !appKey.matches("^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$"))
            throw new IllegalArgumentException("应用标识只能使用 3-50 位小写字母、数字和连字符");
        Instant now = Instant.now();
        Models.BuildTarget target = Models.BuildTarget.of(targetOs, targetArch).normalized();
        Models.Project project;
        if (id == null) {
            project = new Models.Project();
            project.id = UUID.randomUUID().toString();
            project.createdAt = now;
        } else {
            project = store.project(id);
            Models.BuildTarget original = Models.BuildTarget.of(project.targetOs, project.targetArch).normalized();
            if (!original.os.equals(target.os) || !original.arch.equals(target.arch))
                throw new IllegalArgumentException("项目目标架构创建后不可修改，请新建项目后重新绑定制品");
            if (project.appKey != null && !project.appKey.equals(appKey))
                throw new IllegalArgumentException("项目应用标识创建后不可修改，它已经用于约定镜像名称");
        }
        project.name = name.trim();
        project.appKey = appKey;
        project.description = clean(description);
        project.currentVersion = clean(currentVersion);
        project.targetOs = target.os;
        project.targetArch = target.arch;
        project.backendHealthPath = healthPath(backendHealthPath, "/api/health/live", "后端健康路径");
        project.frontendHealthPath = healthPath(frontendHealthPath, "/", "前端健康路径");
        project.updatedAt = now;
        store.putProject(project);
        return project;
    }

    public void delete(String projectId) {
        boolean hasArtifacts = store.artifacts().stream().anyMatch(value -> projectId.equals(value.projectId));
        boolean hasBuilds = store.builds().stream().anyMatch(value -> projectId.equals(value.projectId));
        boolean hasProfiles = store.profiles().stream().anyMatch(value -> projectId.equals(value.projectId));
        if (hasArtifacts || hasBuilds || hasProfiles)
            throw new IllegalArgumentException("项目已有部署配置、制品或构建历史，为保证追溯记录不能删除");
        store.deleteProject(projectId);
    }

    public Models.RepositoryConfig saveRepository(String projectId, String repositoryId, String role,
                                                   String url, String ref, String subdirectory,
                                                   String dockerfile, String authType, String username,
                                                   String secret) {
        Models.Project project = store.project(projectId);
        String normalizedRole = defaultValue(role, "BACKEND").toUpperCase(Locale.ROOT);
        if (!List.of("FRONTEND", "BACKEND").contains(normalizedRole))
            throw new IllegalArgumentException("仓库角色只支持 FRONTEND 或 BACKEND");
        require(url, "仓库地址");
        if (url.startsWith("-")) throw new IllegalArgumentException("仓库地址不能以 - 开头");
        String normalizedAuth = defaultValue(authType, "NONE").toUpperCase(Locale.ROOT);
        if (!List.of("NONE", "HTTPS", "SSH").contains(normalizedAuth))
            throw new IllegalArgumentException("不支持的仓库认证方式");
        Models.RepositoryConfig repository = null;
        if (repositoryId != null) {
            repository = project.repositories.stream().filter(r -> repositoryId.equals(r.id)).findFirst()
                    .orElseThrow(() -> new PlatformStore.NotFoundException("仓库配置不存在：" + repositoryId));
        }
        if (repository == null) {
            boolean duplicateRole = project.repositories.stream().anyMatch(r -> normalizedRole.equals(r.role));
            if (duplicateRole) throw new IllegalArgumentException("每个项目只能配置一个 " + normalizedRole + " 仓库");
            repository = new Models.RepositoryConfig();
            repository.id = UUID.randomUUID().toString();
            project.repositories.add(repository);
        }
        repository.role = normalizedRole;
        repository.url = url.trim();
        repository.ref = defaultValue(ref, "HEAD");
        repository.subdirectory = defaultValue(subdirectory, ".");
        repository.dockerfile = defaultValue(dockerfile, "Dockerfile");
        repository.authType = normalizedAuth;
        repository.username = clean(username);
        if (secret != null && !secret.isBlank()) repository.secretCipher = crypto.encrypt(secret);
        if (!"NONE".equals(normalizedAuth) && (repository.secretCipher == null || repository.secretCipher.isBlank()))
            throw new IllegalArgumentException("认证方式为 " + normalizedAuth + " 时必须提供凭据");
        if ("NONE".equals(normalizedAuth)) repository.secretCipher = null;
        repository.lockedCommit = null;
        repository.updatedAt = Instant.now();
        project.analysis = null;
        project.updatedAt = Instant.now();
        store.putProject(project);
        return repository;
    }

    public void deleteRepository(String projectId, String repositoryId) {
        Models.Project project = store.project(projectId);
        if (!project.repositories.removeIf(r -> repositoryId.equals(r.id)))
            throw new PlatformStore.NotFoundException("仓库配置不存在：" + repositoryId);
        project.analysis = null;
        project.updatedAt = Instant.now();
        store.putProject(project);
    }

    public Models.AnalysisResult analyze(String projectId) throws Exception {
        Models.Project project = store.project(projectId);
        String workspaceName = "analysis/" + project.id + "-" + UUID.randomUUID();
        List<RepositoryService.RepositorySnapshot> snapshots = repositories.checkout(project, workspaceName, ignored -> {});
        Path workspace = snapshots.get(0).repositoryRoot().getParent();
        try {
            Models.AnalysisResult result = analyzer.analyze(snapshots);
            for (RepositoryService.RepositorySnapshot snapshot : snapshots) {
                project.repositories.stream().filter(r -> snapshot.role().equals(r.role)).findFirst()
                        .ifPresent(r -> r.lockedCommit = snapshot.commit());
            }
            project.analysis = result;
            project.updatedAt = Instant.now();
            store.putProject(project);
            return result;
        } finally {
            try { FileSupport.deleteTree(store.workspacesRoot(), workspace); } catch (Exception ignored) {}
        }
    }

    public Models.AnalysisResult confirm(String projectId, Map<String, Boolean> decisions) {
        Models.Project project = store.project(projectId);
        if (project.analysis == null) throw new IllegalArgumentException("请先执行仓库分析");
        for (Models.Finding finding : project.analysis.findings) {
            if (decisions.containsKey(finding.component)) finding.confirmed = Boolean.TRUE.equals(decisions.get(finding.component));
        }
        project.updatedAt = Instant.now();
        store.putProject(project);
        return project.analysis;
    }

    private String clean(String value) { return value == null ? "" : value.trim(); }
    private String healthPath(String value, String fallback, String label) {
        String path = value == null || value.isBlank() ? fallback : value.trim();
        if (!path.matches("^/[A-Za-z0-9/_?&=.%+-]*$")) throw new IllegalArgumentException(label + "格式不正确");
        return path;
    }
    private String defaultValue(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private void require(String value, String label) { if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空"); }
}
