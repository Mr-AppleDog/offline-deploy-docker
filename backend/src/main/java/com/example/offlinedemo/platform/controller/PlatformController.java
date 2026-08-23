package com.example.offlinedemo.platform.controller;

import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.service.ArtifactService;
import com.example.offlinedemo.platform.service.ProfileService;
import com.example.offlinedemo.platform.service.ProjectService;
import com.example.offlinedemo.platform.store.PlatformStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/platform")
public class PlatformController {
    private final PlatformStore store;
    private final ProjectService projects;
    private final ProfileService profiles;
    private final ArtifactService artifacts;

    public PlatformController(PlatformStore store, ProjectService projects, ProfileService profiles,
                              ArtifactService artifacts) {
        this.store = store;
        this.projects = projects;
        this.profiles = profiles;
        this.artifacts = artifacts;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return Map.of(
                "projects", store.projects().size(),
                "profiles", store.profiles().size(),
                "artifacts", store.artifacts().size(),
                "builds", store.builds().size(),
                "runningBuilds", store.countBuildsByStatus("RUNNING") + store.countBuildsByStatus("QUEUED"),
                "architecture", Models.ARCHITECTURE);
    }

    @GetMapping("/projects")
    public List<Map<String, Object>> projects() { return store.projects().stream().map(this::projectView).toList(); }

    @GetMapping("/projects/{id}")
    public Map<String, Object> project(@PathVariable String id) { return projectView(store.project(id)); }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createProject(@RequestBody ProjectInput input) {
        return projectView(projects.save(null, input.name, input.appKey, input.description, input.currentVersion,
                input.backendHealthPath, input.frontendHealthPath));
    }

    @PutMapping("/projects/{id}")
    public Map<String, Object> updateProject(@PathVariable String id, @RequestBody ProjectInput input) {
        return projectView(projects.save(id, input.name, input.appKey, input.description, input.currentVersion,
                input.backendHealthPath, input.frontendHealthPath));
    }

    @DeleteMapping("/projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@PathVariable String id) { store.deleteProject(id); }

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

    @GetMapping("/artifacts")
    public List<Models.Artifact> artifacts() { return store.artifacts(); }

    @GetMapping("/artifacts/components")
    public Map<String, Object> artifactComponents() {
        return Map.of("architecture", Models.ARCHITECTURE, "components", ArtifactService.COMPONENTS.stream().sorted().toList());
    }

    @PostMapping("/artifacts/import")
    @ResponseStatus(HttpStatus.CREATED)
    public Models.Artifact importArtifact(@RequestBody ArtifactInput input) throws Exception {
        return artifacts.importFile(input.component, input.version, input.sourcePath);
    }

    private Map<String, Object> projectView(Models.Project project) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", project.id);
        view.put("name", project.name);
        view.put("appKey", project.appKey);
        view.put("description", project.description);
        view.put("currentVersion", project.currentVersion);
        view.put("backendHealthPath", project.backendHealthPath);
        view.put("frontendHealthPath", project.frontendHealthPath);
        view.put("repositories", project.repositories.stream().map(this::repositoryView).toList());
        view.put("analysis", project.analysis);
        view.put("createdAt", project.createdAt);
        view.put("updatedAt", project.updatedAt);
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
        view.put("mysqlDatabase", profile.mysqlDatabase);
        view.put("mysqlRootUsername", profile.mysqlRootUsername);
        view.put("mysqlUsername", profile.mysqlUsername);
        view.put("redisDatabase", profile.redisDatabase);
        view.put("rabbitmqUsername", profile.rabbitmqUsername);
        view.put("rabbitmqVhost", profile.rabbitmqVhost);
        view.put("minioAccessKey", profile.minioAccessKey);
        view.put("minioBucket", profile.minioBucket);
        view.put("frontendPort", profile.frontendPort);
        view.put("timezone", profile.timezone);
        view.put("javaOptions", profile.javaOptions);
        view.put("secretsConfigured", Map.of("mysqlRoot", profile.mysqlRootPasswordCipher != null,
                "mysql", profile.mysqlPasswordCipher != null, "redis", profile.redisPasswordCipher != null,
                "rabbitmq", profile.rabbitmqPasswordCipher != null, "minio", profile.minioSecretKeyCipher != null));
        view.put("createdAt", profile.createdAt);
        view.put("updatedAt", profile.updatedAt);
        return view;
    }

    public static final class ProjectInput {
        public String name; public String appKey; public String description; public String currentVersion;
        public String backendHealthPath; public String frontendHealthPath;
    }
    public static final class RepositoryInput {
        public String role; public String url; public String ref; public String subdirectory;
        public String dockerfile; public String authType; public String username; public String secret;
    }
    public static final class AnalysisConfirmation { public Map<String, Boolean> decisions; }
    public static final class ArtifactInput { public String component; public String version; public String sourcePath; }
}
