package com.example.offlinedemo.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "kunlun.platform")
public class PlatformProperties {
    private String dataDir = "../.kunlun-builder";
    private String projectRoot = "..";
    private String secretKey = "";
    private int maxAnalysisFiles = 6000;
    private int commandTimeoutMinutes = 60;
    private MetadataProperties metadata = new MetadataProperties();
    private StorageProperties storage = new StorageProperties();

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public String getProjectRoot() { return projectRoot; }
    public void setProjectRoot(String projectRoot) { this.projectRoot = projectRoot; }
    public Path dataDirPath() { return Path.of(dataDir); }
    public Path projectRootPath() { return Path.of(projectRoot); }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public int getMaxAnalysisFiles() { return maxAnalysisFiles; }
    public void setMaxAnalysisFiles(int maxAnalysisFiles) { this.maxAnalysisFiles = maxAnalysisFiles; }
    public int getCommandTimeoutMinutes() { return commandTimeoutMinutes; }
    public void setCommandTimeoutMinutes(int commandTimeoutMinutes) { this.commandTimeoutMinutes = commandTimeoutMinutes; }
    public MetadataProperties getMetadata() { return metadata; }
    public void setMetadata(MetadataProperties metadata) { this.metadata = metadata; }
    public StorageProperties getStorage() { return storage; }
    public void setStorage(StorageProperties storage) { this.storage = storage; }

    /** 平台自身元数据的持久化后端（真实 MySQL 或本地 JSON 回退）。 */
    public static class MetadataProperties {
        private String jdbcUrl = "";
        private String username = "";
        private String password = "";

        public String getJdbcUrl() { return jdbcUrl; }
        public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public boolean enabled() { return jdbcUrl != null && !jdbcUrl.isBlank(); }
    }

    /** 制品与交付物的对象存储后端（MinIO 或本地文件回退）。 */
    public static class StorageProperties {
        private String type = "local";
        private String minioEndpoint = "";
        private String minioAccessKey = "";
        private String minioSecretKey = "";
        private String minioBucket = "kunlun-platform";

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getMinioEndpoint() { return minioEndpoint; }
        public void setMinioEndpoint(String minioEndpoint) { this.minioEndpoint = minioEndpoint; }
        public String getMinioAccessKey() { return minioAccessKey; }
        public void setMinioAccessKey(String minioAccessKey) { this.minioAccessKey = minioAccessKey; }
        public String getMinioSecretKey() { return minioSecretKey; }
        public void setMinioSecretKey(String minioSecretKey) { this.minioSecretKey = minioSecretKey; }
        public String getMinioBucket() { return minioBucket; }
        public void setMinioBucket(String minioBucket) { this.minioBucket = minioBucket; }
        public boolean minioEnabled() {
            return "minio".equalsIgnoreCase(type) && minioEndpoint != null && !minioEndpoint.isBlank();
        }
    }
}
