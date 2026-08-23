package com.example.offlinedemo.platform.store;

import com.example.offlinedemo.platform.domain.Models;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 本地 JSON 文件后端（幂等、原子替换），作为未配置 MySQL 时的回退。 */
public class JsonMetadataStore implements MetadataStore {
    private final ObjectMapper objectMapper;
    private final Path stateFile;

    public JsonMetadataStore(ObjectMapper objectMapper, Path dataDir) {
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.stateFile = dataDir.resolve("platform-state.json");
    }

    @Override
    public Models.PlatformState load() throws Exception {
        Files.createDirectories(stateFile.getParent());
        if (Files.isRegularFile(stateFile))
            return objectMapper.readValue(stateFile.toFile(), Models.PlatformState.class);
        return new Models.PlatformState();
    }

    @Override
    public void save(Models.PlatformState state) throws Exception {
        Files.createDirectories(stateFile.getParent());
        Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), state);
        try {
            Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public boolean remote() { return false; }
}