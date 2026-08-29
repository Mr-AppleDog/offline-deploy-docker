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
        public Map<String, SqlScript> sqlScripts = new LinkedHashMap<>();
        public Map<String, BuildTask> builds = new LinkedHashMap<>();
        public Map<String, ImageExportTask> imageExportTasks = new LinkedHashMap<>();
    }

    public static final class Project {
        public String id;
        public String name;
        public String appKey;
        public String description;
        public String currentVersion;
        /** 项目创建时固化的离线交付目标。 */
        public String targetOs = DEFAULT_OS;
        public String targetArch = DEFAULT_ARCH;
        public String backendHealthPath;
        public String frontendHealthPath;
        public List<RepositoryConfig> repositories = new ArrayList<>();
        /** 前端、后端各自绑定的 Docker Registry 镜像路径。 */
        public List<ImageRegistryConfig> imageRegistries = new ArrayList<>();
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

    /** 项目应用镜像仓库。凭证仅保存 AES-GCM 密文，对外视图不得返回 secretCipher。 */
    public static final class ImageRegistryConfig {
        public String id;
        /** FRONTEND / BACKEND。 */
        public String role;
        /** Registry 服务根地址，例如 https://harbor.example.com。 */
        public String registryUrl;
        /** Registry V2 中的镜像路径，例如 team/app-backend。 */
        public String repository;
        /** Docker 守护进程拉取时使用的主机和端口；为空时沿用 registryUrl。 */
        public String pullAuthority;
        /** 是否由平台依据项目 appKey 自动发现和维护。 */
        public boolean managed;
        /** NONE / BASIC。 */
        public String authType;
        public String username;
        public String secretCipher;
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
        /** UPLOAD / REGISTRY_EXPORT。 */
        public String sourceType = "UPLOAD";
        /** 应用镜像所属项目；中间件和基础设施制品为空。 */
        public String projectId;
        public String projectName;
        /** FRONTEND / BACKEND，仅应用镜像使用。 */
        public String applicationRole;
        public String gitRepositoryId;
        public String gitRepositoryUrl;
        public String gitRef;
        public String gitCommit;
        /** 通过 Registry 导出时使用的项目镜像仓库配置。 */
        public String imageRegistryId;
        /** docker pull/save 制品的原始镜像引用及校验后的镜像 ID。 */
        public String imageReference;
        /** docker save TAR 内实际保存的镜像引用；旧制品为空时由构建器兼容推断。 */
        public String archiveImageReference;
        public String imageId;
        public String imageDigest;
        public Instant createdAt;
    }

    /** 从镜像仓库拉取指定平台镜像并导出 docker-save tar 的异步任务。 */
    public static final class ImageExportTask {
        public String id;
        public String component;
        public String version;
        public String imageReference;
        /** 应用镜像任务所属项目与角色；中间件任务为空。 */
        public String projectId;
        public String projectName;
        public String applicationRole;
        public String gitRepositoryId;
        public String gitRepositoryUrl;
        public String gitRef;
        public String gitCommit;
        public String imageRegistryId;
        public String targetOs;
        public String targetArch;
        public String status;
        public String stage;
        public int progress;
        public String artifactId;
        public boolean reused;
        public String imageId;
        public String imageDigest;
        public String error;
        public Instant createdAt;
        public Instant startedAt;
        public Instant finishedAt;
    }

    /** 数据库脚本制品：通过页面上传入库，构建时按 id 引用拷进交付包。 */
    public static final class SqlScript {
        public String id;
        /** INIT = 初始化 SQL（随 bootstrap 包进 database/init）；MIGRATION = 迁移 SQL（进 database/migrations/&lt;版本&gt;）。 */
        public String kind;
        public String name;
        public String targetVersion;
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
        public List<String> dbInitSqlIds = new ArrayList<>();
        public List<String> dbMigrationSqlIds = new ArrayList<>();
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
