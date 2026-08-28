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

    private CatalogEntry entry(String component, String imageRepo, List<String> architectures) {
        CatalogEntry value = new CatalogEntry();
        value.component = component;
        value.displayName = component;
        value.imageRepo = imageRepo;
        value.architectures = architectures;
        return value;
    }
}
