package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.catalog.CatalogEntry;
import com.example.offlinedemo.platform.catalog.MiddlewareCatalog;
import com.example.offlinedemo.platform.config.PlatformProperties;
import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.store.BlobStore;
import com.example.offlinedemo.platform.store.PlatformStore;
import com.example.offlinedemo.platform.util.CommandRunner;
import com.example.offlinedemo.platform.util.FileSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class BuildWorker {
    private final PlatformStore store;
    private final ProfileService profiles;
    private final ArtifactService artifacts;
    private final RepositoryService repositories;
    private final ComposeRenderer composeRenderer;
    private final MiddlewareCatalog catalog;
    private final BlobStore blobStore;
    private final CommandRunner commands;
    private final ObjectMapper objectMapper;
    private final Path projectRoot;

    public BuildWorker(PlatformStore store, ProfileService profiles, ArtifactService artifacts,
                       RepositoryService repositories, ComposeRenderer composeRenderer,
                       MiddlewareCatalog catalog, BlobStore blobStore,
                       CommandRunner commands, ObjectMapper objectMapper, PlatformProperties properties) {
        this.store = store;
        this.profiles = profiles;
        this.artifacts = artifacts;
        this.repositories = repositories;
        this.composeRenderer = composeRenderer;
        this.catalog = catalog;
        this.blobStore = blobStore;
        this.commands = commands;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.projectRoot = properties.projectRootPath().toAbsolutePath().normalize();
    }

    public void run(String taskId) {
        Path workspace = null;
        try {
            Models.BuildTask task = store.build(taskId);
            Models.BuildTarget target = targetOf(task);
            store.updateBuild(taskId, value -> {
                value.status = "RUNNING";
                value.stage = "构建环境预检查";
                value.progress = 3;
                value.startedAt = Instant.now();
            });
            log(taskId, "开始构建 " + task.packageType + "，目标 " + target.description() + "（" + target.ociPlatform() + "）");
            Models.Project project = store.project(task.projectId);
            ProfileService.ResolvedProfile profile = profiles.resolve(task.profileId);
            if (profile.profile().revision != task.spec.profileRevision)
                throw new IllegalStateException("部署配置在任务创建后已修改，请重新创建构建任务");

            commands.run(List.of("docker", "info"), projectRoot, this::discard);
            Path deployScripts = projectRoot.resolve("deploy/scripts").normalize();
            if (!Files.isDirectory(deployScripts)) throw new IllegalStateException("找不到部署脚本目录：" + deployScripts);

            workspace = FileSupport.safeResolve(store.workspacesRoot(), "build/" + taskId, "构建目录");
            Files.createDirectories(workspace);
            stage(taskId, "校验构建素材", 10);
            Map<String, Models.Artifact> selected = new LinkedHashMap<>();
            for (Models.Artifact artifact : artifacts.selected(task.spec.artifactIds)) selected.put(artifact.component, artifact);

            stage(taskId, "加载应用镜像", 24);
            List<ImageRecord> imageRecords = new ArrayList<>();
            for (String role : task.spec.updateScope) {
                ImageRecord appRecord = loadApplicationArtifact(task, project, role, selected, workspace);
                if (appRecord != null) imageRecords.add(appRecord);
            }
            if ("BOOTSTRAP".equals(task.packageType)) {
                stage(taskId, "校验中间件镜像", 48);
                for (String component : middlewareComponents(profile)) {
                    CatalogEntry entry = catalog.entry(component);
                    Models.Artifact artifact = selected.get(component);
                    Path localTar = artifactLocal(artifact, workspace.resolve(".cache"));
                    commands.run(List.of("docker", "load", "--input", localTar.toString()), projectRoot,
                            line -> log(taskId, line));
                    String image = entry.imageRepo + ":" + artifact.version;
                    ImageIdentity identity = inspectImage(image, target);
                    imageRecords.add(new ImageRecord(image, identity.id, target.ociPlatform(),
                            null, artifact.sha256, localTar));
                }
            }

            stage(taskId, "组装离线包", 62);
            PackageResult packageResult = assemble(task, project, profile, selected, imageRecords, workspace);
            stage(taskId, "生成校验文件并压缩", 82);
            Path checksumFile = packageResult.root.resolve("SHA256SUMS");
            writeChecksums(packageResult.root, checksumFile);
            commands.run(List.of("tar", "-czf", packageResult.archive.toString(), "-C",
                    packageResult.root.getParent().toString(), packageResult.root.getFileName().toString()),
                    projectRoot, line -> log(taskId, line));
            commands.run(List.of("tar", "-tzf", packageResult.archive.toString()), projectRoot, this::discard);
            String archiveHash = FileSupport.sha256(packageResult.archive);
            Files.writeString(Path.of(packageResult.archive + ".sha256"),
                    archiveHash + "  " + packageResult.archive.getFileName() + System.lineSeparator(), StandardCharsets.US_ASCII);

            stage(taskId, "交付物自检完成", 100);
            BlobStore.BlobRef deliveryRef = blobStore.remote()
                    ? blobStore.put(packageResult.archive, "deliveries/" + project.appKey + "/" + task.targetVersion + "/" + packageResult.archive.getFileName())
                    : BlobStore.BlobRef.local(packageResult.archive);
            store.updateBuild(taskId, value -> {
                value.status = "SUCCEEDED";
                value.stage = "构建成功";
                value.progress = 100;
                value.artifactPath = packageResult.archive.toString();
                value.artifactName = packageResult.archive.getFileName().toString();
                value.artifactStoreType = deliveryRef.storeType();
                value.artifactObjectKey = "minio".equals(deliveryRef.storeType()) ? deliveryRef.ref() : null;
                value.sha256 = archiveHash;
                value.finishedAt = Instant.now();
            });
            log(taskId, "构建成功：" + packageResult.archive.getFileName());
            Path sources = workspace.resolve("sources");
            if (Files.exists(sources)) {
                try { FileSupport.deleteTree(store.workspacesRoot(), sources); } catch (IOException ignored) {}
            }
        } catch (Exception e) {
            String message = rootMessage(e);
            try { log(taskId, "构建失败：" + message); } catch (Exception ignored) {}
            store.updateBuild(taskId, value -> {
                value.status = "FAILED";
                value.stage = "构建失败";
                value.error = message;
                value.finishedAt = Instant.now();
            });
        }
    }

    /**
     * 应用镜像从已导入的制品 tar 加载并校验。若未导入该角色对应的 app 制品，返回 null 跳过
     *（application/images 该角色留空，install 阶段只启动中间件）。
     */
    private ImageRecord loadApplicationArtifact(Models.BuildTask task, Models.Project project,
                                                String role, Map<String, Models.Artifact> selected,
                                                Path workspace) throws Exception {
        Models.BuildTarget target = targetOf(task);
        String suffix = role.toLowerCase(Locale.ROOT);
        String component = "app-" + suffix;
        Models.Artifact artifact = selected.get(component);
        if (artifact == null) {
            log(task.id, "未选择 " + component + " 应用镜像制品，application/images 该角色留空");
            return null;
        }
        String image = project.appKey + "-" + suffix + ":" + task.targetVersion;
        Path localTar = artifactLocal(artifact, workspace.resolve(".cache"));
        log(task.id, "加载应用镜像 " + image + "（制品 " + artifact.fileName + "）");
        commands.run(List.of("docker", "load", "--input", localTar.toString()), projectRoot,
                line -> log(task.id, line));
        ImageIdentity identity = inspectImage(image, target);
        return new ImageRecord(image, identity.id, target.ociPlatform(), null, artifact.sha256, localTar);
    }

    private PackageResult assemble(Models.BuildTask task, Models.Project project,
                                   ProfileService.ResolvedProfile profile,
                                   Map<String, Models.Artifact> selected,
                                   List<ImageRecord> records,
                                   Path workspace) throws Exception {
        String revision = task.spec.packageRevision == null || task.spec.packageRevision.isBlank()
                ? "" : "-" + task.spec.packageRevision;
        Models.BuildTarget target = targetOf(task);
        List<CatalogEntry> catalogEntries = catalog.entriesFor(middlewareComponents(profile));
        String packageName = "BOOTSTRAP".equals(task.packageType)
                ? "kunlun-bootstrap-" + task.targetVersion + revision + "-" + target.packageSuffix()
                : "kunlun-app-update-" + task.targetVersion + "-" + target.packageSuffix();
        Path root = workspace.resolve(packageName);
        Files.createDirectories(root);
        Path application = root.resolve("application");
        Files.createDirectories(application);
        int backendVersionChanged = task.spec.updateScope.contains("BACKEND") ? 1 : 0;
        int frontendVersionChanged = task.spec.updateScope.contains("FRONTEND") ? 1 : 0;
        String backendVersion = backendVersionChanged == 1 ? task.targetVersion : task.fromVersion;
        String frontendVersion = frontendVersionChanged == 1 ? task.targetVersion : task.fromVersion;
        java.util.Set<String> includedApps = new java.util.LinkedHashSet<>(ArtifactService.APP_IMAGE_COMPONENTS);
        includedApps.retainAll(selected.keySet());
        Files.writeString(application.resolve("compose.app.yml"),
                composeRenderer.application(profile, project.appKey, backendVersion, frontendVersion,
                        project.backendHealthPath, project.frontendHealthPath, catalogEntries, includedApps, target), StandardCharsets.UTF_8);

        Path appImageDir = application.resolve("images").resolve(task.targetVersion);
        Files.createDirectories(appImageDir);
        for (ImageRecord record : records) {
            if (!record.image.startsWith(project.appKey + "-")) continue;
            Path destination = appImageDir.resolve(record.source.getFileName());
            Files.copy(record.source, destination, StandardCopyOption.REPLACE_EXISTING);
            record.relativeTar = root.relativize(destination).toString().replace('\\', '/');
            writeSideChecksum(destination, record.sha256);
        }

        Map<String, String> middlewareVersions = new LinkedHashMap<>();
        if ("BOOTSTRAP".equals(task.packageType)) {
            Files.createDirectories(root.resolve("database/init"));
            Files.createDirectories(root.resolve("database/migrations").resolve(task.targetVersion));
            Files.createDirectories(root.resolve("middleware"));
            for (String component : middlewareComponents(profile)) {
                Models.Artifact artifact = selected.get(component);
                middlewareVersions.put(component, artifact.version);
                Path imageDir = root.resolve("middleware").resolve(component).resolve("image");
                Files.createDirectories(imageDir);
                String name = component + "-" + artifact.version.replaceAll("[^A-Za-z0-9._+-]", "-") + "-" + target.packageSuffix() + ".tar";
                Path destination = imageDir.resolve(name);
                Files.copy(artifactLocal(artifact, workspace.resolve(".cache")), destination, StandardCopyOption.REPLACE_EXISTING);
                writeSideChecksum(destination, artifact.sha256);
                records.stream().filter(record -> (catalog.entry(component).imageRepo + ":" + artifact.version).equals(record.image))
                        .findFirst().ifPresent(record -> record.relativeTar = root.relativize(destination).toString().replace('\\', '/'));
            }
            Files.writeString(root.resolve("middleware/compose.middleware.yml"),
                    composeRenderer.middleware(profile, catalogEntries, middlewareVersions, target), StandardCharsets.UTF_8);
            writeMiddlewareSpec(root, profile);
            copyDockerMedia(root, selected, target, workspace.resolve(".cache"));
            FileSupport.copyTree(projectRoot.resolve("deploy/scripts"), root.resolve("scripts"));
            parameterizeBootstrapInstaller(root.resolve("scripts/install-bootstrap.sh"), task, selected);
            copyIfExists(projectRoot.resolve("README.md"), root.resolve("README.md"));
            copyIfExists(projectRoot.resolve("部署手册.md"), root.resolve("部署手册.md"));
            FileSupport.copyTree(projectRoot.resolve("docs"), root.resolve("docs"));
            copySqlScripts(task.id, task.spec.dbInitSqlIds, root.resolve("database/init"), workspace.resolve(".cache"));
            if (task.spec.dbMigrationRequired)
                copySqlScripts(task.id, task.spec.dbMigrationSqlIds,
                        root.resolve("database/migrations").resolve(task.targetVersion), workspace.resolve(".cache"));
        } else {
            Path migrations = root.resolve("database/migrations").resolve(task.targetVersion);
            Files.createDirectories(migrations);
            if (task.spec.dbMigrationRequired)
                copySqlScripts(task.id, task.spec.dbMigrationSqlIds, migrations, workspace.resolve(".cache"));
            Files.createDirectories(root.resolve("scripts"));
            copyIfExists(projectRoot.resolve("deploy/scripts/common.sh"), root.resolve("scripts/common.sh"));
            copyIfExists(projectRoot.resolve("deploy/scripts/install-app-update.sh"), root.resolve("scripts/install-app-update.sh"));
            parameterizeAppUpdateInstaller(root.resolve("scripts/install-app-update.sh"), target);
        }

        commands.run(List.of("docker", "compose", "-f", application.resolve("compose.app.yml").toString(),
                "config", "--quiet"), projectRoot, this::discard);
        if ("BOOTSTRAP".equals(task.packageType))
            commands.run(List.of("docker", "compose", "-f", root.resolve("middleware/compose.middleware.yml").toString(),
                    "config", "--quiet"), projectRoot, this::discard);

        writeManifest(root, task, project, profile, selected, backendVersion, frontendVersion);
        writeImages(root, records);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("build-spec.json").toFile(), task.spec);
        Path deliveryDir = store.deliveriesRoot().resolve(project.appKey).resolve(task.targetVersion);
        Files.createDirectories(deliveryDir);
        Path archive = deliveryDir.resolve(packageName + ".tar.gz");
        if (Files.exists(archive) || Files.exists(Path.of(archive + ".sha256")))
            throw new IllegalStateException("同名交付物已经存在，请提高包修订号或目标版本：" + archive.getFileName());
        return new PackageResult(root, archive);
    }

    private void copyDockerMedia(Path root, Map<String, Models.Artifact> selected, Models.BuildTarget target, Path cache) throws Exception {
        Models.Artifact engine = selected.get("docker-engine");
        Models.Artifact compose = selected.get("docker-compose");
        Path install = root.resolve("docker/install");
        Files.createDirectories(install);
        Path engineTarget = install.resolve("docker-" + engine.version + ".tgz");
        Files.copy(artifactLocal(engine, cache), engineTarget, StandardCopyOption.REPLACE_EXISTING);
        writeSideChecksum(engineTarget, engine.sha256);
        Path composeTarget = install.resolve(target.composeBinary());
        Files.copy(artifactLocal(compose, cache), composeTarget, StandardCopyOption.REPLACE_EXISTING);
        writeSideChecksum(composeTarget, compose.sha256);
        copyIfExists(projectRoot.resolve("deploy/docker/daemon.json"), install.resolve("daemon.json"));
        copyIfExists(projectRoot.resolve("deploy/systemd/docker.service"), install.resolve("docker.service"));
    }

    /** 把选中的数据库脚本制品物化并拷进交付包指定目录；文件名冲突时用 id 前缀消歧。 */
    private void copySqlScripts(String taskId, List<String> ids, Path destination, Path cache) throws Exception {
        if (ids == null || ids.isEmpty()) return;
        Files.createDirectories(destination);
        for (String id : ids) {
            Models.SqlScript script = store.sqlScript(id);
            Path local = sqlLocal(script, cache);
            Path target = destination.resolve(script.fileName);
            if (Files.exists(target)) {
                String name = script.fileName;
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                String ext = dot > 0 ? name.substring(dot) : "";
                target = destination.resolve(script.id.substring(0, 8) + "-" + base + ext);
            }
            Files.copy(local, target, StandardCopyOption.REPLACE_EXISTING);
            log(taskId, "数据库脚本入包：" + script.fileName);
        }
    }

    private Path sqlLocal(Models.SqlScript script, Path cacheDir) throws Exception {
        if ("minio".equals(script.storeType)) {
            return blobStore.materialize(new BlobStore.BlobRef("minio", script.objectKey), script.fileName, cacheDir);
        }
        Path local = Path.of(script.storagePath);
        if (!Files.isRegularFile(local)) throw new IllegalStateException("脚本本地文件不存在：" + local);
        return local;
    }

    private void parameterizeBootstrapInstaller(Path installer, Models.BuildTask task,
                                                Map<String, Models.Artifact> selected) throws IOException {
        Models.BuildTarget target = targetOf(task);
        String text = Files.readString(installer, StandardCharsets.UTF_8)
                .replace("readonly REQUIRED_DOCKER_VERSION=29.7.0", "readonly REQUIRED_DOCKER_VERSION=" + selected.get("docker-engine").version)
                .replace("readonly REQUIRED_COMPOSE_VERSION=5.4.0", "readonly REQUIRED_COMPOSE_VERSION=" + selected.get("docker-compose").version)
                .replace("readonly APP_VERSION=1.1.1", "readonly APP_VERSION=" + task.targetVersion)
                .replace("readonly REQUIRED_PLATFORM=linux/amd64", "readonly REQUIRED_PLATFORM=" + target.ociPlatform())
                .replace("docker-compose-linux-x86_64", target.composeBinary())
                .replace("[[ \"$(uname -m)\" == x86_64 ]] || die '目标机架构必须为 x86_64。'",
                        "[[ \"$(uname -m)\" == " + target.unameArch() + " ]] || die '目标机架构必须为 " + target.unameArch() + "。'")
                .replace("[[ \"$record_count\" -eq 6 ]]", "[[ \"$record_count\" -eq 6 ]]");
        Files.writeString(installer, text, StandardCharsets.UTF_8);
    }

    private void parameterizeAppUpdateInstaller(Path installer, Models.BuildTarget target) throws IOException {
        String text = Files.readString(installer, StandardCharsets.UTF_8)
                .replace("linux/amd64", target.ociPlatform());
        Files.writeString(installer, text, StandardCharsets.UTF_8);
    }

    private void writeManifest(Path root, Models.BuildTask task, Models.Project project,
                               ProfileService.ResolvedProfile profile, Map<String, Models.Artifact> selected,
                               String backendVersion, String frontendVersion) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("PACKAGE_TYPE=" + ("BOOTSTRAP".equals(task.packageType) ? "bootstrap" : "app-update"));
        if ("BOOTSTRAP".equals(task.packageType)) lines.add("APP_VERSION=" + task.targetVersion);
        else {
            lines.add("FROM_VERSION=" + task.fromVersion);
            lines.add("TO_VERSION=" + task.targetVersion);
            lines.add("UPDATE_SCOPE=" + String.join(",", task.spec.updateScope));
        }
        lines.add("TARGET_PLATFORM=" + targetOf(task).ociPlatform());
        lines.add("TARGET_OS=" + targetOf(task).os);
        lines.add("TARGET_ARCH=" + targetOf(task).arch);
        lines.add("PROJECT_KEY=" + project.appKey);
        lines.add("DEPLOYMENT_PROFILE_ID=" + profile.profile().id);
        lines.add("DEPLOYMENT_PROFILE_REVISION=" + profile.profile().revision);
        java.util.Set<String> includedApps = new java.util.LinkedHashSet<>(ArtifactService.APP_IMAGE_COMPONENTS);
        includedApps.retainAll(selected.keySet());
        lines.add("BACKEND_IMAGE=" + (includedApps.contains("app-backend") ? project.appKey + "-backend:" + backendVersion : ""));
        lines.add("FRONTEND_IMAGE=" + (includedApps.contains("app-frontend") ? project.appKey + "-frontend:" + frontendVersion : ""));
        lines.add("BACKEND_HEALTH_PATH=" + project.backendHealthPath);
        lines.add("FRONTEND_HEALTH_PATH=" + project.frontendHealthPath);
        lines.add("DB_MIGRATION_REQUIRED=" + task.spec.dbMigrationRequired);
        lines.add("CREDENTIAL_MODE=embedded-compose");
        if ("BOOTSTRAP".equals(task.packageType)) {
            lines.add("DOCKER_VERSION=" + selected.get("docker-engine").version);
            lines.add("COMPOSE_VERSION=" + selected.get("docker-compose").version);
            List<String> components = middlewareComponents(profile);
            lines.add("MIDDLEWARE_COUNT=" + components.size());
            lines.add("MIDDLEWARE_LIST=" + String.join(",", components));
            for (String component : components)
                lines.add(component.toUpperCase(Locale.ROOT) + "_VERSION=" + selected.get(component).version);
        }
        Files.write(root.resolve("manifest.env"), lines, StandardCharsets.US_ASCII);
    }

    private void writeMiddlewareSpec(Path root, ProfileService.ResolvedProfile profile) throws IOException {
        List<CatalogEntry> entries = profile.selectedEntries(catalog);
        Files.writeString(root.resolve("middleware.list"),
                entries.stream().map(e -> e.component).collect(java.util.stream.Collectors.joining("\n")) + "\n",
                StandardCharsets.UTF_8);
        List<Map<String, Object>> spec = new ArrayList<>();
        for (CatalogEntry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("component", e.component);
            m.put("imageRepo", e.imageRepo);
            m.put("category", e.category);
            m.put("backupStrategy", e.backupStrategy == null ? "" : e.backupStrategy);
            m.put("backupCommand", e.backupCommand == null ? "" : e.backupCommand);
            m.put("healthcheck", e.healthcheck.test);
            spec.add(m);
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("middleware.spec.json").toFile(), spec);
    }

    private void writeImages(Path root, List<ImageRecord> records) throws IOException {
        List<String> lines = records.stream().filter(record -> record.relativeTar != null)
                .map(record -> String.join("|", record.image, record.id, record.platform, record.relativeTar, record.sha256))
                .toList();
        Files.write(root.resolve("images.txt"), lines, StandardCharsets.US_ASCII);
    }

    private void writeChecksums(Path root, Path checksumFile) throws IOException {
        List<String> lines = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            for (Path file : stream.filter(Files::isRegularFile).filter(path -> !path.equals(checksumFile))
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                lines.add(FileSupport.sha256(file) + "  " + root.relativize(file).toString().replace('\\', '/'));
            }
        }
        Files.write(checksumFile, lines, StandardCharsets.UTF_8);
    }

    private void writeSideChecksum(Path file, String hash) throws IOException {
        Files.writeString(Path.of(file + ".sha256"), hash + "  " + file.getFileName() + System.lineSeparator(), StandardCharsets.US_ASCII);
    }

    private ImageIdentity inspectImage(String image, Models.BuildTarget target) throws Exception {
        String output = commands.run(List.of("docker", "image", "inspect", "--format",
                "{{.Id}}|{{.Os}}/{{.Architecture}}", image), projectRoot, this::discard).output().trim();
        String[] parts = output.split("\\|", 2);
        if (parts.length != 2 || !target.ociPlatform().equals(parts[1]))
            throw new IllegalStateException("镜像架构不是 " + target.ociPlatform() + "：" + image + "（" + output + "）");
        return new ImageIdentity(parts[0], parts[1]);
    }

    private static List<String> middlewareComponents(ProfileService.ResolvedProfile profile) {
        return profile.profile().middleware.stream().map(mc -> mc.component).toList();
    }

    private Path artifactLocal(Models.Artifact artifact, Path cacheDir) throws Exception {
        if ("minio".equals(artifact.storeType)) {
            return blobStore.materialize(new BlobStore.BlobRef("minio", artifact.objectKey), artifact.fileName, cacheDir);
        }
        Path local = Path.of(artifact.storagePath);
        if (!Files.isRegularFile(local)) throw new IllegalStateException("制品本地文件不存在：" + local);
        return local;
    }

    private static Models.BuildTarget targetOf(Models.BuildTask task) {
        return Models.BuildTarget.of(task.spec.targetOs, task.spec.targetArch).normalized();
    }

    private void stage(String taskId, String stage, int progress) {
        store.updateBuild(taskId, value -> { value.stage = stage; value.progress = progress; });
        log(taskId, stage);
    }

    private void log(String taskId, String message) {
        try {
            Path file = store.logsRoot().resolve(taskId + ".log");
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write("[" + Instant.now() + "] " + message);
                writer.newLine();
            }
        } catch (IOException ignored) {}
    }

    private void copyIfExists(Path source, Path destination) throws IOException {
        if (!Files.isRegularFile(source)) throw new IOException("缺少构建资源：" + source);
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private void discard(String ignored) {}
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String value = current.getMessage();
        return value == null || value.isBlank() ? current.getClass().getSimpleName() : value;
    }

    private static final class ImageRecord {
        private final String image;
        private final String id;
        private final String platform;
        private String relativeTar;
        private final String sha256;
        private final Path source;
        private ImageRecord(String image, String id, String platform, String relativeTar, String sha256, Path source) {
            this.image = image; this.id = id; this.platform = platform; this.relativeTar = relativeTar;
            this.sha256 = sha256; this.source = source;
        }
    }
    private record ImageIdentity(String id, String platform) {}
    private record PackageResult(Path root, Path archive) {}
}
