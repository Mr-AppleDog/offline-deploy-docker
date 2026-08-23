package com.example.offlinedemo.platform.config;

import com.example.offlinedemo.platform.store.BlobStore;
import com.example.offlinedemo.platform.store.JdbcMetadataStore;
import com.example.offlinedemo.platform.store.JsonMetadataStore;
import com.example.offlinedemo.platform.store.LocalBlobStore;
import com.example.offlinedemo.platform.store.MetadataStore;
import com.example.offlinedemo.platform.store.MinioBlobStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 依据配置选择平台自身持久化后端：
 * - 元数据：配置 kunlun.platform.metadata.jdbc-url 时用真实 MySQL，否则回退本地 JSON。
 * - 制品/交付物：配置 kunlun.platform.storage.type=minio 时用 MinIO，否则回退本地文件。
 */
@Configuration
public class PersistenceConfig {

    @Bean
    public MetadataStore metadataStore(PlatformProperties properties, ObjectMapper objectMapper) {
        PlatformProperties.MetadataProperties m = properties.getMetadata();
        if (m.enabled()) {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(m.getJdbcUrl());
            dataSource.setUsername(m.getUsername());
            dataSource.setPassword(m.getPassword());
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSource.setMaximumPoolSize(4);
            JdbcMetadataStore store = new JdbcMetadataStore(new JdbcTemplate(dataSource), objectMapper);
            store.ensureSchema();
            return store;
        }
        return new JsonMetadataStore(objectMapper, properties.dataDirPath());
    }

    @Bean
    public BlobStore blobStore(PlatformProperties properties) {
        PlatformProperties.StorageProperties s = properties.getStorage();
        if (s.minioEnabled()) {
            return new MinioBlobStore(s.getMinioEndpoint(), s.getMinioAccessKey(), s.getMinioSecretKey(), s.getMinioBucket());
        }
        return new LocalBlobStore();
    }
}