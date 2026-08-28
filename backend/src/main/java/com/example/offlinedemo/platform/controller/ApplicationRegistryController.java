package com.example.offlinedemo.platform.controller;

import com.example.offlinedemo.platform.service.ApplicationRegistryService;
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

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/platform/projects/{projectId}/image-registries")
public class ApplicationRegistryController {
    private final PlatformStore store;
    private final ApplicationRegistryService registries;

    public ApplicationRegistryController(PlatformStore store, ApplicationRegistryService registries) {
        this.store = store;
        this.registries = registries;
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String projectId) {
        return store.project(projectId).imageRegistries.stream().map(registries::view).toList();
    }

    @PostMapping("/auto-bind")
    public List<Map<String, Object>> autoBind(@PathVariable String projectId) {
        return registries.autoBind(projectId).stream().map(registries::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@PathVariable String projectId,
                                      @RequestBody ApplicationRegistryService.RegistryInput input) {
        return registries.view(registries.save(projectId, null, input));
    }

    @PutMapping("/{registryId}")
    public Map<String, Object> update(@PathVariable String projectId, @PathVariable String registryId,
                                      @RequestBody ApplicationRegistryService.RegistryInput input) {
        return registries.view(registries.save(projectId, registryId, input));
    }

    @DeleteMapping("/{registryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String projectId, @PathVariable String registryId) {
        registries.delete(projectId, registryId);
    }

    @GetMapping("/{registryId}/tags")
    public Map<String, Object> tags(@PathVariable String projectId, @PathVariable String registryId) {
        var registry = registries.config(projectId, registryId);
        var catalog = registries.images(projectId, registryId);
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("repository", registry.registryUrl.replaceFirst("^https?://", "")
                + "/" + registry.repository + ":{tag}");
        response.put("tags", catalog.tags());
        response.put("images", catalog.images());
        response.put("unavailableTags", catalog.unavailableTags());
        return response;
    }
}
