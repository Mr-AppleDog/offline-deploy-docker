package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.catalog.MiddlewareCatalog;
import com.example.offlinedemo.platform.config.PlatformProperties;
import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.store.BlobStore;
import com.example.offlinedemo.platform.store.PlatformStore;
import com.example.offlinedemo.platform.util.CommandRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuildWorkerImageArchiveTest {
    @TempDir Path directory;

    @Test
    void resavesApplicationImageWithOfflineReferenceAndValidatesArchiveTags() throws Exception {
        CommandRunner commands = mock(CommandRunner.class);
        BuildWorker worker = worker(commands);
        String image = "docloom-backend:1.0.1";
        Path expected = directory.resolve("workspace/normalized-app-images/app-backend-1.0.1-linux-amd64.tar");
        when(commands.run(anyList(), any(Path.class), any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            if (command.size() >= 2 && "docker".equals(command.get(0)) && "save".equals(command.get(1))) {
                Files.createDirectories(expected.getParent());
                Files.writeString(expected, "normalized image archive");
                return new CommandRunner.Result(0, "");
            }
            if (command.size() >= 2 && "tar".equals(command.get(0)) && "-xOf".equals(command.get(1))) {
                return new CommandRunner.Result(0,
                        "[{\"Config\":\"config.json\",\"RepoTags\":[\"" + image + "\"],\"Layers\":[]}]");
            }
            return new CommandRunner.Result(0, "");
        });

        Path output = worker.normalizeApplicationArchive("build-1", "app-backend", "1.0.1", image,
                Models.BuildTarget.of("kylin-v10", "amd64").normalized(), directory.resolve("workspace"));

        assertThat(output).isEqualTo(expected).isRegularFile();
        verify(commands).run(eq(List.of("docker", "save", "--output", expected.toString(), image)),
                eq(directory), any());
        verify(commands).run(eq(List.of("tar", "-xOf", expected.toString(), "manifest.json")),
                eq(directory), any());
    }

    @Test
    void rejectsNormalizedArchiveWhenOfflineReferenceIsMissing() throws Exception {
        CommandRunner commands = mock(CommandRunner.class);
        BuildWorker worker = worker(commands);
        when(commands.run(anyList(), any(Path.class), any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            if (command.size() >= 2 && "docker".equals(command.get(0)) && "save".equals(command.get(1))) {
                Path output = Path.of(command.get(3));
                Files.createDirectories(output.getParent());
                Files.writeString(output, "incorrect image archive");
                return new CommandRunner.Result(0, "");
            }
            return new CommandRunner.Result(0,
                    "[{\"Config\":\"config.json\",\"RepoTags\":[\"localhost:5000/docloom-backend:sha-f52d490\"],\"Layers\":[]}]");
        });

        assertThatThrownBy(() -> worker.normalizeApplicationArchive("build-1", "app-backend", "1.0.1",
                "docloom-backend:1.0.1", Models.BuildTarget.of("kylin-v10", "amd64").normalized(),
                directory.resolve("workspace")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未保存交付标签 docloom-backend:1.0.1");
    }

    private BuildWorker worker(CommandRunner commands) {
        PlatformStore store = mock(PlatformStore.class);
        when(store.logsRoot()).thenReturn(directory.resolve("logs"));
        PlatformProperties properties = new PlatformProperties();
        properties.setProjectRoot(directory.toString());
        return new BuildWorker(store, mock(ProfileService.class), mock(ArtifactService.class),
                mock(RepositoryService.class), mock(ComposeRenderer.class), mock(MiddlewareCatalog.class),
                mock(BlobStore.class), commands, new ObjectMapper(), properties);
    }
}
