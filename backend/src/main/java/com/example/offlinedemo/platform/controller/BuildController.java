package com.example.offlinedemo.platform.controller;

import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.service.BuildService;
import com.example.offlinedemo.platform.store.PlatformStore;
import org.springframework.core.io.FileSystemResource;
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
import java.util.Map;

@RestController
@RequestMapping("/api/platform/builds")
public class BuildController {
    private final PlatformStore store;
    private final BuildService builds;

    public BuildController(PlatformStore store, BuildService builds) {
        this.store = store;
        this.builds = builds;
    }

    @GetMapping
    public List<Models.BuildTask> builds() { return store.builds(); }

    @GetMapping("/{id}")
    public Models.BuildTask build(@PathVariable String id) { return store.build(id); }

    @PostMapping
    public Models.BuildTask create(@RequestBody BuildService.BuildInput input) { return builds.create(input); }

    @GetMapping(value = "/{id}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public String logs(@PathVariable String id) throws Exception {
        store.build(id);
        Path log = store.logsRoot().resolve(id + ".log");
        return Files.isRegularFile(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        Models.BuildTask task = store.build(id);
        if (!"SUCCEEDED".equals(task.status) || task.artifactPath == null)
            throw new IllegalArgumentException("该任务还没有可下载的交付物");
        Path path = Path.of(task.artifactPath).toAbsolutePath().normalize();
        if (!path.startsWith(store.deliveriesRoot().toAbsolutePath().normalize()) || !Files.isRegularFile(path))
            throw new IllegalArgumentException("交付物不存在或路径无效");
        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(path.getFileName().toString(), StandardCharsets.UTF_8).build().toString())
                .body(resource);
    }

    @GetMapping("/{id}/checksum")
    public Map<String, String> checksum(@PathVariable String id) {
        Models.BuildTask task = store.build(id);
        if (!"SUCCEEDED".equals(task.status)) throw new IllegalArgumentException("构建尚未成功");
        return Map.of("fileName", task.artifactName, "sha256", task.sha256);
    }
}
