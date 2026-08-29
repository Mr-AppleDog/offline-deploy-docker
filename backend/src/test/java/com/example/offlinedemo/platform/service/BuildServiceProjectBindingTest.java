package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.store.PlatformStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuildServiceProjectBindingTest {
    private final PlatformStore store = mock(PlatformStore.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final BuildWorker worker = mock(BuildWorker.class);
    private final BuildService service = new BuildService(store, executor, worker);

    @Test
    void usesFixedProjectArchitectureAndCarriesApplicationCommits() {
        Models.Project project = project();
        Models.DeploymentProfile profile = profile();
        when(store.project("project-1")).thenReturn(project);
        when(store.profile("profile-1")).thenReturn(profile);
        Map<String, Models.Artifact> artifacts = new LinkedHashMap<>();
        artifacts.put("docker", artifact("docker", "docker-engine", "29.7.0", null, null));
        artifacts.put("compose", artifact("compose", "docker-compose", "5.4.0", null, null));
        artifacts.put("backend", artifact("backend", "app-backend", "1.2.3", "BACKEND", "abc1234def"));
        artifacts.put("frontend", artifact("frontend", "app-frontend", "1.2.3", "FRONTEND", "fedcba9876"));
        artifacts.forEach((id, artifact) -> when(store.artifact(id)).thenReturn(artifact));

        BuildService.BuildInput input = input(List.copyOf(artifacts.keySet()));
        Models.BuildTask task = service.create(input);

        assertThat(task.spec.targetArch).isEqualTo("amd64");
        assertThat(task.sourceCommits).containsEntry("BACKEND", "abc1234def")
                .containsEntry("FRONTEND", "fedcba9876");
        verify(store).putBuild(task);
        verify(executor).execute(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsArchitectureDifferentFromProject() {
        when(store.project("project-1")).thenReturn(project());
        when(store.profile("profile-1")).thenReturn(profile());
        BuildService.BuildInput input = input(List.of());
        input.targetArch = "arm64";

        assertThatThrownBy(() -> service.create(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须使用项目创建时固定的 amd64");
    }

    @Test
    void rejectsProfileBoundToAnotherProject() {
        Models.DeploymentProfile profile = profile();
        profile.projectId = "project-2";
        when(store.project("project-1")).thenReturn(project());
        when(store.profile("profile-1")).thenReturn(profile);

        assertThatThrownBy(() -> service.create(input(List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("部署配置属于其他项目");
    }

    @Test
    void updateMustStartFromProfileDeployedVersion() {
        when(store.project("project-1")).thenReturn(project());
        when(store.profile("profile-1")).thenReturn(profile());
        BuildService.BuildInput input = input(List.of());
        input.packageType = "APP_UPDATE";
        input.fromVersion = "1.1.0";

        assertThatThrownBy(() -> service.create(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已部署版本 1.0.0");
    }

    private BuildService.BuildInput input(List<String> artifactIds) {
        BuildService.BuildInput input = new BuildService.BuildInput();
        input.projectId = "project-1";
        input.profileId = "profile-1";
        input.packageType = "BOOTSTRAP";
        input.targetVersion = "1.2.3";
        input.packageRevision = "r1";
        input.artifactIds = artifactIds;
        input.targetArch = "amd64";
        return input;
    }

    private Models.Project project() {
        Models.Project project = new Models.Project();
        project.id = "project-1";
        project.name = "Example";
        project.appKey = "example-app";
        project.targetOs = "kylin-v10";
        project.targetArch = "amd64";
        return project;
    }

    private Models.DeploymentProfile profile() {
        Models.DeploymentProfile profile = new Models.DeploymentProfile();
        profile.id = "profile-1";
        profile.projectId = "project-1";
        profile.name = "Production";
        profile.deployedVersion = "1.0.0";
        profile.revision = 1;
        profile.targetOs = "kylin-v10";
        profile.targetArch = "amd64";
        profile.middleware = List.of();
        return profile;
    }

    private Models.Artifact artifact(String id, String component, String version, String role, String commit) {
        Models.Artifact artifact = new Models.Artifact();
        artifact.id = id;
        artifact.component = component;
        artifact.version = version;
        artifact.architecture = "linux/amd64";
        if (role != null) {
            artifact.projectId = "project-1";
            artifact.applicationRole = role;
            artifact.gitCommit = commit;
        }
        return artifact;
    }
}
