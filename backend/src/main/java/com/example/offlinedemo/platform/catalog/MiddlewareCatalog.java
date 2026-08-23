package com.example.offlinedemo.platform.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 classpath:platform/catalog/middleware-catalog.json 加载中间件注册表。
 */
@Component
public class MiddlewareCatalog {
    private final ObjectMapper objectMapper;
    private Map<String, CatalogEntry> entries = new LinkedHashMap<>();
    private List<String> orderedComponents = new ArrayList<>();

    public MiddlewareCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() throws Exception {
        ClassPathResource resource = new ClassPathResource("platform/catalog/middleware-catalog.json");
        try (InputStream in = resource.getInputStream()) {
            CatalogFile file = objectMapper.readValue(in, CatalogFile.class);
            entries = new LinkedHashMap<>();
            orderedComponents = new ArrayList<>();
            for (CatalogEntry entry : file.components) {
                entries.put(entry.component, entry);
                orderedComponents.add(entry.component);
            }
        }
    }

    public CatalogEntry entry(String component) {
        CatalogEntry entry = entries.get(component);
        if (entry == null) throw new IllegalArgumentException("未注册的中间件组件：" + component);
        return entry;
    }

    public boolean exists(String component) { return entries.containsKey(component); }

    public List<CatalogEntry> entriesFor(List<String> components) {
        return components.stream().map(this::entry).toList();
    }

    public List<CatalogEntry> all() {
        return orderedComponents.stream().map(entries::get).toList();
    }

    public static final class CatalogFile {
        public List<CatalogEntry> components = new ArrayList<>();
    }
}