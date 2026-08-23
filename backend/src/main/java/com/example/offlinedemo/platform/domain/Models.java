package com.example.offlinedemo.platform.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Models {
    private Models() {}

    public static final String ARCHITECTURE = "linux/amd64";

    public static final class PlatformState {
        public Map<String, Project> projects = new LinkedHashMap<>();
        public Map<String, DeploymentProfile> profiles = new LinkedHashMap<>();
        public Map<String, Artifact> artifacts = new LinkedHashMap<>();
        public Map<String, BuildTask> builds = new LinkedHashMap<>();
    }

    public static final class Project {
        public String id;
        public String name;
        public String appKey;
        public String description;
        public String currentVersion;
        public String backendHealthPath;
        public String frontendHealthPath;
        public List<RepositoryConfig> repositories = new ArrayList<>();
        public AnalysisResult analysis;
        public Instant createdAt;
        public Instant updatedAt;
    }

    public static final class RepositoryConfig {
        public String id;
        public String role;
        public String url;
        public String ref;
        public String subdirectory;
        public String dockerfile;
        public String authType;
        public String username;
        public String secretCipher;
        public String lockedCommit;
        public Instant updatedAt;
    }

    public static final class AnalysisResult {
        public Instant analyzedAt;
        public List<Finding> findings = new ArrayList<>();
        public Map<String, String> commits = new LinkedHashMap<>();
        public int scannedFiles;
    }

    public static final class Finding {
        public String component;
        public String category;
        public String label;
        public double confidence;
        public boolean confirmed;
        public List<String> evidence = new ArrayList<>();
    }

    public static final class DeploymentProfile {
        public String id;
        public String name;
        public String environment;
        public int revision;
        public String mysqlDatabase;
        public String mysqlRootUsername;
        public String mysqlRootPasswordCipher;
        public String mysqlUsername;
        public String mysqlPasswordCipher;
        public int redisDatabase;
        public String redisPasswordCipher;
        public String rabbitmqUsername;
        public String rabbitmqPasswordCipher;
        public String rabbitmqVhost;
        public String minioAccessKey;
        public String minioSecretKeyCipher;
        public String minioBucket;
        public int frontendPort;
        public String timezone;
        public String javaOptions;
        public Instant createdAt;
        public Instant updatedAt;
    }

    public static final class Artifact {
        public String id;
        public String component;
        public String version;
        public String architecture = ARCHITECTURE;
        public String fileName;
        public String storagePath;
        public String sha256;
        public long size;
        public Instant createdAt;
    }

    public static final class BuildSpec {
        public String projectId;
        public String profileId;
        public int profileRevision;
        public String packageType;
        public String fromVersion;
        public String targetVersion;
        public String packageRevision;
        public List<String> artifactIds = new ArrayList<>();
        public List<String> updateScope = new ArrayList<>();
        public boolean dbMigrationRequired;
        public String databaseInitDirectory;
        public String databaseMigrationDirectory;
    }

    public static final class BuildTask {
        public String id;
        public String projectId;
        public String projectName;
        public String profileId;
        public String profileName;
        public String packageType;
        public String targetVersion;
        public String fromVersion;
        public String status;
        public String stage;
        public int progress;
        public String artifactPath;
        public String artifactName;
        public String sha256;
        public String error;
        public Map<String, String> sourceCommits = new LinkedHashMap<>();
        public BuildSpec spec;
        public Instant createdAt;
        public Instant startedAt;
        public Instant finishedAt;
    }
}
