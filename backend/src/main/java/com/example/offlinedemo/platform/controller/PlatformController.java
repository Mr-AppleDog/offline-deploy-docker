package com.example.offlinedemo.platform.controller;

import com.example.offlinedemo.platform.catalog.CatalogEntry;
import com.example.offlinedemo.platform.catalog.MiddlewareCatalog;
import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.service.ArtifactService;
import com.example.offlinedemo.platform.service.ProfileService;
import com.example.offlinedemo.platform.service.ProjectService;
import com.example.offlinedemo.platform.service.RepositoryService;
import com.example.offlinedemo.platform.service.SqlScriptService;
import com.example.offlinedemo.platform.store.PlatformStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/platform")
public class PlatformController {
    private final PlatformStore store;
    private final ProjectService projects;
    private final RepositoryService repositories;
    private final ProfileService profiles;
    private final ArtifactService artifacts;
    private final SqlScriptService sqlScripts;
    private final MiddlewareCatalog catalog;

    public PlatformController(PlatformStore store, ProjectService projects, RepositoryService repositories, ProfileService profiles,
                              ArtifactService artifacts, SqlScriptService sqlScripts, MiddlewareCatalog catalog) {
        this.store = store;
        this.projects = projects;
        this.repositories = repositories;
        this.profiles = profiles;
        this.artifacts = artifacts;
        this.sqlScripts = sqlScripts;
        this.catalog = catalog;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return Map.of(
                "projects", store.projects().size(),
                "profiles", store.profiles().size(),
                "artifacts", store.artifacts().size(),
                "sqlScripts", store.sqlScripts().size(),
                "builds", store.builds().size(),
                "runningBuilds", store.countBuildsByStatus("RUNNING") + store.countBuildsByStatus("QUEUED"),
                "imageExportTasks", store.imageExportTasks().size(),
                "runningImageExports", store.countImageExportTasksByStatus("RUNNING") + store.countImageExportTasksByStatus("QUEUED"),
                "architecture", Models.ARCHITECTURE,
                "targets", Models.supportedTargetViews());
    }

    @GetMapping("/projects")
    public List<Map<String, Object>> projects() { return store.projects().stream().map(this::projectView).toList(); }

    @GetMapping("/projects/{id}")
    public Map<String, Object> project(@PathVariable String id) { return projectView(store.project(id)); }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createProject(@RequestBody ProjectInput input) {
        return projectView(projects.save(null, input.name, input.appKey, input.description, input.currentVersion,
                input.targetOs, input.targetArch, input.backendHealthPath, input.frontendHealthPath));
    }

    @PutMapping("/projects/{id}")
    public Map<String, Object> updateProject(@PathVariable String id, @RequestBody ProjectInput input) {
        return projectView(projects.save(id, input.name, input.appKey, input.description, input.currentVersion,
                input.targetOs, input.targetArch, input.backendHealthPath, input.frontendHealthPath));
    }

    @DeleteMapping("/projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@PathVariable String id) { projects.delete(id); }

    @PostMapping("/projects/{projectId}/repositories")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createRepository(@PathVariable String projectId, @RequestBody RepositoryInput input) {
        return repositoryView(projects.saveRepository(projectId, null, input.role, input.url, input.ref,
                input.subdirectory, input.dockerfile, input.authType, input.username, input.secret));
    }

    @PutMapping("/projects/{projectId}/repositories/{repositoryId}")
    public Map<String, Object> updateRepository(@PathVariable String projectId, @PathVariable String repositoryId,
                                                @RequestBody RepositoryInput input) {
        return repositoryView(projects.saveRepository(projectId, repositoryId, input.role, input.url, input.ref,
                input.subdirectory, input.dockerfile, input.authType, input.username, input.secret));
    }

    @DeleteMapping("/projects/{projectId}/repositories/{repositoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRepository(@PathVariable String projectId, @PathVariable String repositoryId) {
        projects.deleteRepository(projectId, repositoryId);
    }

    @GetMapping("/projects/{projectId}/repositories/{role}/latest-commit")
    public Map<String, String> latestRepositoryCommit(@PathVariable String projectId,
                                                       @PathVariable String role) throws Exception {
        RepositoryService.ResolvedCommit resolved = repositories.resolveCommit(projectId, role);
        return Map.of("role", resolved.role(), "repositoryId", resolved.repositoryId(),
                "repositoryUrl", resolved.repositoryUrl(), "ref", resolved.ref(), "commit", resolved.commit());
    }

    @PostMapping("/projects/{id}/analyze")
    public Models.AnalysisResult analyze(@PathVariable String id) throws Exception { return projects.analyze(id); }

    @PutMapping("/projects/{id}/analysis")
    public Models.AnalysisResult confirm(@PathVariable String id, @RequestBody AnalysisConfirmation input) {
        return projects.confirm(id, input.decisions == null ? Map.of() : input.decisions);
    }

    @GetMapping("/profiles")
    public List<Map<String, Object>> profiles() { return store.profiles().stream().map(this::profileView).toList(); }

    @PostMapping("/profiles")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createProfile(@RequestBody ProfileService.ProfileInput input) {
        return profileView(profiles.save(null, input));
    }

    @PutMapping("/profiles/{id}")
    public Map<String, Object> updateProfile(@PathVariable String id, @RequestBody ProfileService.ProfileInput input) {
        return profileView(profiles.save(id, input));
    }

    @PostMapping("/profiles/generate-password")
    public Map<String, String> generatePassword() { return Map.of("password", profiles.generatePassword()); }

    @GetMapping("/middleware/catalog")
    public List<Map<String, Object>> middlewareCatalog() { return catalog.all().stream().map(this::catalogView).toList(); }

    @GetMapping("/artifacts")
    public List<Models.Artifact> artifacts() { return store.artifacts(); }

    @GetMapping("/artifacts/components")
    public Map<String, Object> artifactComponents() {
        List<String> components = new java.util.ArrayList<>(ArtifactService.INFRA_COMPONENTS);
        components.addAll(ArtifactService.APP_IMAGE_COMPONENTS);
        catalog.all().forEach(entry -> components.add(entry.component));
        return Map.of("architecture", Models.ARCHITECTURE, "targets", Models.supportedTargetViews(),
                "components", components);
    }

    @PostMapping("/artifacts/import")
    @ResponseStatus(HttpStatus.CREATED)
    public Models.Artifact importArtifact(@RequestPart("file") MultipartFile file,
                                           @RequestParam String component,
                                           @RequestParam String version,
                                           @RequestParam(defaultValue = "amd64") String arch) throws Exception {
        if (ArtifactService.APP_IMAGE_COMPONENTS.contains(component == null ? "" : component.toLowerCase()))
            throw new IllegalArgumentException("应用镜像必须从项目页面上传并绑定 Git 提交");
        Path staged = stageUpload(file);
        try {
            return artifacts.importFile(component, version, staged.toString(), arch);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    @PostMapping("/projects/{projectId}/application-artifacts/import")
    @ResponseStatus(HttpStatus.CREATED)
    public Models.Artifact importApplicationArtifact(@PathVariable String projectId,
                                                      @RequestPart("file") MultipartFile file,
                                                      @RequestParam String role,
                                                      @RequestParam String version,
                                                      @RequestParam(required = false) String gitCommit) throws Exception {
        Path staged = stageUpload(file);
        try {
            String resolvedCommit = gitCommit == null || gitCommit.isBlank()
                    ? repositories.resolveCommit(projectId, role).commit() : gitCommit;
            return artifacts.importApplication(projectId, role, version, resolvedCommit, staged.toString());
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    @GetMapping("/sql-scripts")
    public List<Models.SqlScript> sqlScripts() { return store.sqlScripts(); }

    @PostMapping("/sql-scripts")
    @ResponseStatus(HttpStatus.CREATED)
    public Models.SqlScript importSqlScript(@RequestPart("file") MultipartFile file,
                                             @RequestParam String kind,
                                             @RequestParam String name,
                                             @RequestParam String targetVersion) throws Exception {
        Path staged = stageUpload(file);
        try {
            return sqlScripts.importFile(kind, name, targetVersion, staged.toString());
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    @DeleteMapping("/sql-scripts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSqlScript(@PathVariable String id) { store.deleteSqlScript(id); }

    /** 把上传文件暂存到 workspaces/uploads，保留原始扩展名（校验依赖后缀），调用方负责删除。 */
    private Path stageUpload(MultipartFile file) throws IOException {
        Path dir = store.workspacesRoot().resolve("uploads");
        Files.createDirectories(dir);
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) original = "upload.bin";
        String safe = original.replaceAll("[^A-Za-z0-9._+-]", "-");
        Path target = dir.resolve(UUID.randomUUID() + "-" + safe);
        file.transferTo(target.toFile());
        return target;
    }

    private Map<String, Object> projectView(Models.Project project) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", project.id);
        view.put("name", project.name);
        view.put("appKey", project.appKey);
        view.put("description", project.description);
        view.put("currentVersion", project.currentVersion);
        view.put("targetOs", project.targetOs);
        view.put("targetArch", project.targetArch);
        view.put("backendHealthPath", project.backendHealthPath);
        view.put("frontendHealthPath", project.frontendHealthPath);
        view.put("repositories", project.repositories.stream().map(this::repositoryView).toList());
        view.put("imageRegistries", project.imageRegistries.stream().map(this::imageRegistryView).toList());
        view.put("analysis", project.analysis);
        view.put("createdAt", project.createdAt);
        view.put("updatedAt", project.updatedAt);
        return view;
    }

    private Map<String, Object> imageRegistryView(Models.ImageRegistryConfig registry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", registry.id);
        view.put("role", registry.role);
        view.put("registryUrl", registry.registryUrl);
        view.put("repository", registry.repository);
        view.put("pullAuthority", registry.pullAuthority == null ? "" : registry.pullAuthority);
        view.put("managed", registry.managed);
        view.put("authType", registry.authType);
        view.put("username", registry.username == null ? "" : registry.username);
        view.put("credentialConfigured", registry.secretCipher != null && !registry.secretCipher.isBlank());
        view.put("updatedAt", registry.updatedAt);
        return view;
    }

    private Map<String, Object> repositoryView(Models.RepositoryConfig repository) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", repository.id);
        view.put("role", repository.role);
        view.put("url", repository.url);
        view.put("ref", repository.ref);
        view.put("subdirectory", repository.subdirectory);
        view.put("dockerfile", repository.dockerfile);
        view.put("authType", repository.authType);
        view.put("username", repository.username == null ? "" : repository.username);
        view.put("credentialConfigured", repository.secretCipher != null && !repository.secretCipher.isBlank());
        view.put("lockedCommit", repository.lockedCommit == null ? "" : repository.lockedCommit);
        view.put("updatedAt", repository.updatedAt);
        return view;
    }

    private Map<String, Object> profileView(Models.DeploymentProfile profile) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", profile.id);
        view.put("name", profile.name);
        view.put("environment", profile.environment);
        view.put("revision", profile.revision);
        view.put("targetOs", profile.targetOs);
        view.put("targetArch", profile.targetArch);
        view.put("frontendPort", profile.frontendPort);
        view.put("timezone", profile.timezone);
        view.put("javaOptions", profile.javaOptions);
        view.put("middleware", profile.middleware.stream().map(this::credentialView).toList());
        view.put("createdAt", profile.createdAt);
        view.put("updatedAt", profile.updatedAt);
        return view;
    }

    private Map<String, Object> credentialView(Models.MiddlewareCredential mc) {
        CatalogEntry entry = catalog.entry(mc.component);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("component", mc.component);
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Boolean> configured = new LinkedHashMap<>();
        for (CatalogEntry.Credential cred : entry.credentials) {
            String stored = mc.values.getOrDefault(cred.key, "");
            if (cred.secret) {
                configured.put(cred.key, stored != null && !stored.isBlank());
                values.put(cred.key, "");
            } else {
                values.put(cred.key, stored == null ? "" : stored);
            }
        }
        view.put("values", values);
        view.put("configured", configured);
        return view;
    }

    private Map<String, Object> catalogView(CatalogEntry entry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("component", entry.component);
        view.put("displayName", entry.displayName);
        view.put("category", entry.category);
        view.put("imageRepo", entry.imageRepo);
        view.put("architectures", entry.architectures);
        view.put("notes", entry.notes);
        view.put("credentials", entry.credentials.stream().map(cred -> Map.of(
                "key", cred.key, "label", cred.label, "secret", cred.secret, "required", cred.required,
                "envVar", cred.envVar == null ? "" : cred.envVar,
                "defaultValue", cred.defaultValue == null ? "" : cred.defaultValue)).toList());
        return view;
    }

    public static final class ProjectInput {
        public String name; public String appKey; public String description; public String currentVersion;
        public String targetOs; public String targetArch;
        public String backendHealthPath; public String frontendHealthPath;
    }
    public static final class RepositoryInput {
        public String role; public String url; public String ref; public String subdirectory;
        public String dockerfile; public String authType; public String username; public String secret;
    }
    public static final class AnalysisConfirmation { public Map<String, Boolean> decisions; }
}
