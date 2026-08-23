package com.example.offlinedemo.platform.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Models {
    private Models() {}

    public static final String DEFAULT_OS = "kylin-v10";
    public static final String DEFAULT_ARCH = "amd64";
    /** 默认目标平台（OCI 格式），保持向后兼容。 */
    public static final String ARCHITECTURE = "linux/" + DEFAULT_ARCH;

    /** 平台对外支持的目标组合：麒麟 V10 的 amd64 与 arm64（飞腾/鲲鹏）。 */
    public static final List<BuildTarget> SUPPORTED_TARGETS = List.of(
            new BuildTarget("kylin-v10", "amd64"),
            new BuildTarget("kylin-v10", "arm64"));

    /** 支撑列表的序列化视图，供前端渲染目标选择。 */
    public static List<Map<String, String>> supportedTargetViews() {
        return SUPPORTED_TARGETS.stream().map(Models::targetView).toList();
    }

    public static Map<String, String> targetView(BuildTarget target) {
        return Map.of("os", target.os, "arch", target.arch, "platform", target.ociPlatform(),
                "label", target.description());
    }

    /** 构建目标：目标操作系统（如麒麟 V10）+ CPU 架构（amd64/arm64）。 */
    public static final class BuildTarget {
        public String os;
        public String arch;

        public BuildTarget() {}

        public BuildTarget(String os, String arch) {
            this.os = os;
            this.arch = arch;
        }

        public static BuildTarget of(String os, String arch) {
            return new BuildTarget(
                    os == null || os.isBlank() ? DEFAULT_OS : os.trim(),
                    arch == null || arch.isBlank() ? DEFAULT_ARCH : arch.trim());
        }

        public static BuildTarget defaultTarget() { return of(null, null); }

        /** OCI 平台串，例如 linux/amd64、linux/arm64。 */
        public String ociPlatform() { return "linux/" + arch; }

        /** 目标机 uname -m 返回值，x86_64 或 aarch64。 */
        public String unameArch() { return "amd64".equals(arch) ? "x86_64" : "aarch64"; }

        /** Docker Compose 静态二进制文件名（上游命名用 x86_64/aarch64）。 */
        public String composeBinary() { return "docker-compose-linux-" + unameArch(); }

        /** 产物文件名后缀，例如 kylin-v10-amd64。 */
        public String packageSuffix() { return os + "-" + arch; }

        public String description() { return os + " " + arch; }

        public void validate() {
            if (!"amd64".equals(arch) && !"arm64".equals(arch))
                throw new IllegalArgumentException("不支持的目标架构：" + arch);
            if (os == null || !os.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$"))
                throw new IllegalArgumentException("目标操作系统名称格式不正确：" + os);
        }

        public BuildTarget normalized() { validate(); return this; }

        @Override
        public String toString() { return packageSuffix(); }
    }

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
        public String targetOs;
        public String targetArch;
        public int frontendPort;
        public String timezone;
        public String javaOptions;
        public List<MiddlewareCredential> middleware = new ArrayList<>();
        public Instant createdAt;
        public Instant updatedAt;
    }

    /** 单个中间件在部署配置中的凭据（组件 -> 凭证键 -> 明文或密文）。 */
    public static final class MiddlewareCredential {
        public String component;
        public Map<String, String> values = new LinkedHashMap<>();
    }

    public static final class Artifact {
        public String id;
        public String component;
        public String version;
        public String architecture = ARCHITECTURE;
        public String fileName;
        public String storagePath;
        public String objectKey;
        public String storeType = "local";
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
        public String targetOs;
        public String targetArch;
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
        public String artifactStoreType = "local";
        public String artifactObjectKey;
        public String sha256;
        public String error;
        public Map<String, String> sourceCommits = new LinkedHashMap<>();
        public BuildSpec spec;
        public Instant createdAt;
        public Instant startedAt;
        public Instant finishedAt;
    }
}
