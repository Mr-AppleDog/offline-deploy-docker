package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.catalog.CatalogEntry;
import com.example.offlinedemo.platform.catalog.MiddlewareCatalog;
import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.store.PlatformStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageExportServiceTest {
    private final PlatformStore store = mock(PlatformStore.class);
    private final MiddlewareCatalog catalog = mock(MiddlewareCatalog.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final ImageExportWorker worker = mock(ImageExportWorker.class);
    private final ImageExportService service = new ImageExportService(store, catalog, executor, worker);

    @Test
    void reusesExistingTarForSameComponentVersionAndArchitecture() {
        CatalogEntry mysql = entry("mysql", "mysql", List.of("amd64", "arm64"));
        Models.Artifact existing = new Models.Artifact();
        existing.id = "artifact-1";
        existing.component = "mysql";
        existing.version = "8.0";
        existing.architecture = "linux/amd64";
        when(catalog.entry("mysql")).thenReturn(mysql);
        when(store.artifacts()).thenReturn(List.of(existing));

        ImageExportService.ImageExportInput input = new ImageExportService.ImageExportInput();
        input.component = "mysql";
        input.version = "8.0";
        input.targetArch = "amd64";
        Models.ImageExportTask task = service.create(input);

        assertThat(task.status).isEqualTo("SUCCEEDED");
        assertThat(task.reused).isTrue();
        assertThat(task.artifactId).isEqualTo("artifact-1");
        verify(store).putImageExportTask(task);
        verify(executor, never()).execute(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsArchitectureNotSupportedByCatalogEntry() {
        when(catalog.entry("mysql")).thenReturn(entry("mysql", "mysql", List.of("amd64")));
        ImageExportService.ImageExportInput input = new ImageExportService.ImageExportInput();
        input.component = "mysql";
        input.version = "8.0";
        input.targetArch = "arm64";

        assertThatThrownBy(() -> service.create(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持 arm64");
    }

    @Test
    void createsProjectApplicationImageExportFromBoundRegistry() {
        Models.Project project = new Models.Project();
        project.id = "project-1";
        project.name = "订单系统";
        project.appKey = "order";
        project.targetOs = "kylin-v10";
        project.targetArch = "arm64";
        Models.RepositoryConfig source = new Models.RepositoryConfig();
        source.id = "git-1";
        source.role = "BACKEND";
        source.url = "https://git.example.com/team/backend.git";
        source.ref = "main";
        project.repositories.add(source);
        Models.ImageRegistryConfig registry = new Models.ImageRegistryConfig();
        registry.id = "registry-1";
        registry.role = "BACKEND";
        registry.registryUrl = "https://harbor.example.com";
        registry.repository = "team/order-backend";
        registry.authType = "NONE";
        project.imageRegistries.add(registry);
        when(store.project("project-1")).thenReturn(project);
        Models.Artifact legacyArtifact = new Models.Artifact();
        legacyArtifact.component = "app-backend";
        legacyArtifact.version = "1.2.3";
        legacyArtifact.architecture = "linux/arm64";
        legacyArtifact.projectId = "project-1";
        legacyArtifact.imageReference = "harbor.example.com/team/order-backend:v1.2.3";
        legacyArtifact.gitCommit = "abcdef1234567";
        when(store.artifacts()).thenReturn(List.of(legacyArtifact));
        when(store.imageExportTasks()).thenReturn(List.of());

        ImageExportService.ImageExportInput input = new ImageExportService.ImageExportInput();
        input.projectId = "project-1";
        input.applicationRole = "BACKEND";
        input.registryId = "registry-1";
        input.tag = "v1.2.3";
        input.version = "1.2.3";
        input.gitCommit = "abcdef1234567";
        input.targetArch = "arm64";

        Models.ImageExportTask task = service.create(input);

        assertThat(task.component).isEqualTo("app-backend");
        assertThat(task.imageReference).isEqualTo("harbor.example.com/team/order-backend:v1.2.3");
        assertThat(task.projectId).isEqualTo("project-1");
        assertThat(task.applicationRole).isEqualTo("BACKEND");
        assertThat(task.gitCommit).isEqualTo("abcdef1234567");
        assertThat(task.targetArch).isEqualTo("arm64");
        assertThat(task.status).isEqualTo("QUEUED");
        assertThat(task.reused).isFalse();
        verify(store).putImageExportTask(task);
        verify(executor).execute(org.mockito.ArgumentMatchers.any());
    }

    private CatalogEntry entry(String component, String imageRepo, List<String> architectures) {
        CatalogEntry value = new CatalogEntry();
        value.component = component;
        value.displayName = component;
        value.imageRepo = imageRepo;
        value.architectures = architectures;
        return value;
    }
}
