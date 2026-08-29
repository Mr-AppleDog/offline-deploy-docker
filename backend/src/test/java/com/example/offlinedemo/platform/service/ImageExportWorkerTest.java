package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.config.PlatformProperties;
import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.security.CryptoService;
import com.example.offlinedemo.platform.store.PlatformStore;
import com.example.offlinedemo.platform.util.CommandRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageExportWorkerTest {
    @TempDir Path directory;

    @Test
    void retagsRegistryImageToOfflineApplicationReferenceBeforeSaving() throws Exception {
        PlatformStore store = mock(PlatformStore.class);
        ArtifactService artifacts = mock(ArtifactService.class);
        CommandRunner commands = mock(CommandRunner.class);
        CryptoService crypto = mock(CryptoService.class);
        PlatformProperties properties = new PlatformProperties();
        properties.setProjectRoot(directory.toString());

        Models.ImageExportTask task = new Models.ImageExportTask();
        task.id = "export-1";
        task.component = "app-backend";
        task.version = "1.0.0";
        task.imageReference = "localhost:5000/docloom-backend:sha-f52d490";
        task.projectId = "project-1";
        task.applicationRole = "BACKEND";
        task.imageRegistryId = "registry-1";
        task.targetOs = "kylin-v10";
        task.targetArch = "amd64";

        Models.Project project = new Models.Project();
        project.id = task.projectId;
        project.appKey = "docloom";
        Models.ImageRegistryConfig registry = new Models.ImageRegistryConfig();
        registry.id = task.imageRegistryId;
        registry.role = "BACKEND";
        registry.authType = "NONE";
        project.imageRegistries.add(registry);

        when(store.imageExportTask(task.id)).thenReturn(task);
        when(store.project(project.id)).thenReturn(project);
        when(store.workspacesRoot()).thenReturn(directory.resolve("workspaces"));
        when(store.logsRoot()).thenReturn(directory.resolve("logs"));
        when(commands.run(anyList(), any(Path.class), anyMap(), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    List<String> command = invocation.getArgument(0);
                    if (command.contains("{{.Id}}|{{.Os}}/{{.Architecture}}"))
                        return new CommandRunner.Result(0, "sha256:image-id|linux/amd64");
                    if (command.contains("{{json .RepoDigests}}"))
                        return new CommandRunner.Result(0, "[\"localhost:5000/docloom-backend@sha256:digest\"]");
                    return new CommandRunner.Result(0, "");
                });
        Models.Artifact artifact = new Models.Artifact();
        artifact.id = "artifact-1";
        when(artifacts.importFile(eq("app-backend"), eq("1.0.0"), anyString(), eq("amd64"),
                any(ArtifactService.ImportMetadata.class))).thenReturn(artifact);

        new ImageExportWorker(store, artifacts, commands, crypto, new ObjectMapper(), properties).run(task.id);

        verify(commands).run(eq(List.of("docker", "tag", task.imageReference, "docloom-backend:1.0.0")),
                any(Path.class), anyMap(), any(Consumer.class));
        verify(commands).run(org.mockito.ArgumentMatchers.argThat(command ->
                        command.size() == 5 && "docker".equals(command.get(0)) && "save".equals(command.get(1))
                                && "docloom-backend:1.0.0".equals(command.get(4))),
                any(Path.class), anyMap(), any(Consumer.class));
        ArgumentCaptor<ArtifactService.ImportMetadata> metadata =
                ArgumentCaptor.forClass(ArtifactService.ImportMetadata.class);
        verify(artifacts).importFile(eq("app-backend"), eq("1.0.0"), anyString(), eq("amd64"),
                metadata.capture());
        assertThat(metadata.getValue().imageReference).isEqualTo(task.imageReference);
        assertThat(metadata.getValue().archiveImageReference).isEqualTo("docloom-backend:1.0.0");
    }
}
