package com.example.offlinedemo.platform.controller;

import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.service.ImageExportService;
import com.example.offlinedemo.platform.store.BlobStore;
import com.example.offlinedemo.platform.store.PlatformStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/platform")
public class ImageExportController {
    private final PlatformStore store;
    private final ImageExportService imageExports;
    private final BlobStore blobStore;

    public ImageExportController(PlatformStore store, ImageExportService imageExports, BlobStore blobStore) {
        this.store = store;
        this.imageExports = imageExports;
        this.blobStore = blobStore;
    }

    @GetMapping("/image-export-tasks")
    public List<Models.ImageExportTask> tasks() { return store.imageExportTasks(); }

    @GetMapping("/image-export-tasks/{id}")
    public Models.ImageExportTask task(@PathVariable String id) { return store.imageExportTask(id); }

    @PostMapping("/image-export-tasks")
    public Models.ImageExportTask create(@RequestBody ImageExportService.ImageExportInput input) {
        return imageExports.create(input);
    }

    @GetMapping(value = "/image-export-tasks/{id}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public String logs(@PathVariable String id) throws Exception {
        store.imageExportTask(id);
        Path log = store.logsRoot().resolve("image-export-" + id + ".log");
        return Files.isRegularFile(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
    }

    @GetMapping("/artifacts/{id}/download")
    public ResponseEntity<Resource> downloadArtifact(@PathVariable String id) throws Exception {
        Models.Artifact artifact = store.artifact(id);
        Resource body;
        if ("minio".equals(artifact.storeType)) {
            body = new InputStreamResource(blobStore.open(new BlobStore.BlobRef("minio", artifact.objectKey)));
        } else {
            Path path = Path.of(artifact.storagePath).toAbsolutePath().normalize();
            if (!path.startsWith(store.artifactsRoot().toAbsolutePath().normalize()) || !Files.isRegularFile(path))
                throw new IllegalArgumentException("制品不存在或路径无效");
            body = new FileSystemResource(path);
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(artifact.fileName, StandardCharsets.UTF_8).build().toString())
                .body(body);
    }
}
