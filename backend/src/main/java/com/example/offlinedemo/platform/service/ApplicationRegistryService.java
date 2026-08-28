package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.config.PlatformProperties;
import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.security.CryptoService;
import com.example.offlinedemo.platform.store.PlatformStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 管理项目的前后端 Docker Registry 绑定，并通过 Registry V2 API 浏览镜像元数据。 */
@Service
public class ApplicationRegistryService {
    private static final Pattern REPOSITORY = Pattern.compile(
            "^[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*$");
    private static final Pattern CHALLENGE_PARAMETER = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final Pattern TAG_COMMIT = Pattern.compile(
            "(?:^|[-_.])sha[-_.]?([0-9a-f]{7,64})(?=$|[-_.])", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX_COMMIT = Pattern.compile("^[0-9a-f]{7,64}$", Pattern.CASE_INSENSITIVE);
    private static final String MANIFEST_ACCEPT = String.join(", ",
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json");

    private final PlatformStore store;
    private final CryptoService crypto;
    private final ObjectMapper objectMapper;
    private final PlatformProperties properties;
    private volatile HttpClient http;

    public ApplicationRegistryService(PlatformStore store, CryptoService crypto, ObjectMapper objectMapper,
                                      PlatformProperties properties) {
        this(store, crypto, objectMapper, properties, null);
    }

    ApplicationRegistryService(PlatformStore store, CryptoService crypto, ObjectMapper objectMapper,
                               PlatformProperties properties, HttpClient http) {
        this.store = store;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.http = http;
    }

    /**
     * 从平台配置的 Registry 自动发现 appKey-backend 和 appKey-frontend/appKey-ui。
     * 每次同步都会收敛为一条后端和一条前端绑定，顺便修复历史重复或错误端口配置。
     */
    public List<Models.ImageRegistryConfig> autoBind(String projectId) {
        Models.Project project = store.project(projectId);
        String registryUrl = normalizeRegistryUrl(properties.getApplicationRegistryUrl());
        String pullAuthority = normalizePullAuthority(properties.getApplicationRegistryPullAuthority(), registryUrl);
        List<String> repositories = catalog(registryUrl);
        String appKey = normalizeRepository(project.appKey);
        String backend = findRepository(repositories, List.of(appKey + "-backend"));
        String frontend = findRepository(repositories, List.of(appKey + "-frontend", appKey + "-ui"));
        List<String> missing = new ArrayList<>();
        if (backend == null) missing.add(appKey + "-backend");
        if (frontend == null) missing.add(appKey + "-frontend / " + appKey + "-ui");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Registry " + registryUrl + " 中没有找到：" + String.join("、", missing)
                    + "；当前目录：" + String.join("、", repositories));
        }

        Instant now = Instant.now();
        Models.ImageRegistryConfig backendConfig = managedConfig(project, "BACKEND", backend,
                registryUrl, pullAuthority, now);
        Models.ImageRegistryConfig frontendConfig = managedConfig(project, "FRONTEND", frontend,
                registryUrl, pullAuthority, now);
        project.imageRegistries = new ArrayList<>(List.of(backendConfig, frontendConfig));
        project.updatedAt = now;
        store.putProject(project);
        return project.imageRegistries;
    }

    public Models.ImageRegistryConfig save(String projectId, String registryId, RegistryInput input) {
        Models.Project project = store.project(projectId);
        String role = normalizeRole(input.role);
        String registryUrl = normalizeRegistryUrl(input.registryUrl);
        String repository = normalizeRepository(input.repository);
        String authType = input.authType == null ? "NONE" : input.authType.trim().toUpperCase(Locale.ROOT);
        if (!List.of("NONE", "BASIC").contains(authType))
            throw new IllegalArgumentException("镜像仓库认证只支持 NONE 或 BASIC");

        Models.ImageRegistryConfig registry = null;
        if (registryId != null) {
            registry = config(project, registryId);
            if (!role.equals(registry.role)
                    && project.imageRegistries.stream().anyMatch(value -> role.equals(value.role)))
                throw new IllegalArgumentException(role + " 已绑定镜像仓库");
        } else if (project.imageRegistries.stream().anyMatch(value -> role.equals(value.role))) {
            throw new IllegalArgumentException(role + " 已绑定镜像仓库，请编辑现有绑定");
        }
        if (registry == null) {
            registry = new Models.ImageRegistryConfig();
            registry.id = UUID.randomUUID().toString();
            project.imageRegistries.add(registry);
        }

        registry.role = role;
        registry.registryUrl = registryUrl;
        registry.repository = repository;
        registry.pullAuthority = URI.create(registryUrl).getRawAuthority();
        registry.managed = false;
        registry.authType = authType;
        registry.username = clean(input.username);
        if (input.secret != null && !input.secret.isBlank()) registry.secretCipher = crypto.encrypt(input.secret);
        if ("BASIC".equals(authType)) {
            if (registry.username == null || registry.username.isBlank())
                throw new IllegalArgumentException("私有镜像仓库必须填写用户名");
            if (registry.secretCipher == null || registry.secretCipher.isBlank())
                throw new IllegalArgumentException("私有镜像仓库必须填写密码或访问令牌");
        } else {
            registry.username = null;
            registry.secretCipher = null;
        }
        registry.updatedAt = Instant.now();
        project.updatedAt = registry.updatedAt;
        store.putProject(project);
        return registry;
    }

    public void delete(String projectId, String registryId) {
        Models.Project project = store.project(projectId);
        if (!project.imageRegistries.removeIf(value -> registryId.equals(value.id)))
            throw new PlatformStore.NotFoundException("镜像仓库绑定不存在：" + registryId);
        project.updatedAt = Instant.now();
        store.putProject(project);
    }

    public Models.ImageRegistryConfig config(String projectId, String registryId) {
        return config(store.project(projectId), registryId);
    }

    /** 读取标签并展开 OCI/Docker manifest，返回创建时间、Git commit、架构和大小。 */
    public ImageCatalog images(String projectId, String registryId) {
        Models.Project project = store.project(projectId);
        Models.ImageRegistryConfig registry = config(project, registryId);
        List<String> tags = tags(registry);
        List<ImageInfo> images = new ArrayList<>();
        List<UnavailableImage> unavailable = new ArrayList<>();
        for (String tag : tags) {
            try {
                images.add(imageInfo(registry, tag, project.targetArch));
            } catch (Exception exception) {
                unavailable.add(new UnavailableImage(tag, rootMessage(exception)));
            }
        }
        images.sort(Comparator.comparing(ImageInfo::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ImageInfo::tag, Comparator.reverseOrder()));
        return new ImageCatalog(tags, images, unavailable);
    }

    public List<String> tags(String projectId, String registryId) {
        return tags(config(projectId, registryId));
    }

    public Map<String, Object> view(Models.ImageRegistryConfig registry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", registry.id);
        view.put("role", registry.role);
        view.put("registryUrl", registry.registryUrl);
        view.put("repository", registry.repository);
        view.put("pullAuthority", registry.pullAuthority == null ? "" : registry.pullAuthority);
        view.put("managed", registry.managed);
        view.put("authType", registry.authType);
        view.put("username", registry.username == null ? "" : registry.username);
        view.put("credentialConfigured", registry.secretCipher != null && !registry.secretCipher.isBlank());
        view.put("updatedAt", registry.updatedAt);
        return view;
    }

    public static String imageReference(Models.ImageRegistryConfig registry, String tag) {
        String authority = registry.pullAuthority;
        if (authority == null || authority.isBlank()) authority = URI.create(registry.registryUrl).getRawAuthority();
        return authority + "/" + registry.repository + ":" + tag;
    }

    public static String loginServer(Models.ImageRegistryConfig registry) {
        String authority = registry.pullAuthority;
        return authority == null || authority.isBlank()
                ? URI.create(registry.registryUrl).getRawAuthority() : authority;
    }

    private Models.ImageRegistryConfig managedConfig(Models.Project project, String role, String repository,
                                                      String registryUrl, String pullAuthority, Instant now) {
        Models.ImageRegistryConfig registry = project.imageRegistries.stream()
                .filter(value -> role.equals(value.role) && repository.equals(value.repository))
                .findFirst()
                .orElseGet(() -> project.imageRegistries.stream()
                        .filter(value -> role.equals(value.role)).findFirst().orElse(null));
        if (registry == null) {
            registry = new Models.ImageRegistryConfig();
            registry.id = UUID.randomUUID().toString();
        }
        registry.role = role;
        registry.registryUrl = registryUrl;
        registry.repository = repository;
        registry.pullAuthority = pullAuthority;
        registry.managed = true;
        registry.authType = "NONE";
        registry.username = null;
        registry.secretCipher = null;
        registry.updatedAt = now;
        return registry;
    }

    private List<String> catalog(String registryUrl) {
        Models.ImageRegistryConfig registry = new Models.ImageRegistryConfig();
        registry.registryUrl = registryUrl;
        registry.repository = "_catalog";
        registry.authType = "NONE";
        try {
            HttpResponse<String> response = request(URI.create(registryUrl + "/v2/_catalog?n=1000"),
                    registry, "application/json");
            JsonNode repositoriesNode = objectMapper.readTree(response.body()).path("repositories");
            List<String> repositories = new ArrayList<>();
            if (repositoriesNode.isArray()) repositoriesNode.forEach(value -> {
                if (value.isTextual() && REPOSITORY.matcher(value.asText()).matches()) repositories.add(value.asText());
            });
            repositories.sort(String::compareTo);
            return repositories;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取 Registry 目录被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("读取 Registry 目录失败：" + rootMessage(exception), exception);
        }
    }

    private String findRepository(List<String> repositories, List<String> candidates) {
        for (String candidate : candidates) {
            if (repositories.contains(candidate)) return candidate;
        }
        for (String candidate : candidates) {
            String suffix = "/" + candidate;
            String match = repositories.stream().filter(value -> value.endsWith(suffix)).findFirst().orElse(null);
            if (match != null) return match;
        }
        return null;
    }

    private List<String> tags(Models.ImageRegistryConfig registry) {
        URI uri = URI.create(registry.registryUrl + "/v2/" + registry.repository + "/tags/list?n=200");
        try {
            HttpResponse<String> response = request(uri, registry, "application/json");
            JsonNode tagsNode = objectMapper.readTree(response.body()).path("tags");
            List<String> tags = new ArrayList<>();
            if (tagsNode.isArray()) tagsNode.forEach(value -> {
                if (value.isTextual() && !value.asText().isBlank()) tags.add(value.asText());
            });
            tags.sort(Comparator.reverseOrder());
            return tags;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取镜像标签被中断", exception);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("读取镜像标签失败：" + rootMessage(exception), exception);
        }
    }

    private ImageInfo imageInfo(Models.ImageRegistryConfig registry, String tag, String targetArch) throws Exception {
        HttpResponse<String> topResponse = request(manifestUri(registry, tag), registry, MANIFEST_ACCEPT);
        JsonNode manifest = objectMapper.readTree(topResponse.body());
        String digest = topResponse.headers().firstValue("Docker-Content-Digest").orElse("");
        String architecture = null;
        long size = 0;

        JsonNode manifests = manifest.path("manifests");
        if (manifests.isArray()) {
            JsonNode descriptor = null;
            for (JsonNode candidate : manifests) {
                JsonNode platform = candidate.path("platform");
                if ("linux".equals(platform.path("os").asText())
                        && targetArch.equals(platform.path("architecture").asText())) {
                    descriptor = candidate;
                    break;
                }
            }
            if (descriptor == null) throw new IllegalStateException("镜像不包含 linux/" + targetArch + " 架构");
            String childDigest = descriptor.path("digest").asText("");
            if (childDigest.isBlank()) throw new IllegalStateException("镜像索引缺少目标架构 manifest digest");
            size += descriptor.path("size").asLong(0);
            manifest = objectMapper.readTree(request(manifestUri(registry, childDigest), registry, MANIFEST_ACCEPT).body());
            architecture = "linux/" + targetArch;
        }

        JsonNode configDescriptor = manifest.path("config");
        String configDigest = configDescriptor.path("digest").asText("");
        if (configDigest.isBlank()) throw new IllegalStateException("镜像 manifest 缺少 config digest");
        size += configDescriptor.path("size").asLong(0);
        JsonNode layers = manifest.path("layers");
        if (layers.isArray()) for (JsonNode layer : layers) size += layer.path("size").asLong(0);
        JsonNode config = objectMapper.readTree(request(blobUri(registry, configDigest), registry,
                "application/octet-stream, application/json").body());
        if (architecture == null) {
            String os = config.path("os").asText("linux");
            String arch = config.path("architecture").asText(targetArch);
            if (!targetArch.equals(arch)) throw new IllegalStateException("镜像架构为 " + os + "/" + arch
                    + "，项目需要 linux/" + targetArch);
            architecture = os + "/" + arch;
        }

        JsonNode labels = config.path("config").path("Labels");
        String revision = label(labels, "org.opencontainers.image.revision");
        String gitCommit = validCommit(revision) ? revision.toLowerCase(Locale.ROOT) : commitFromTag(tag);
        String gitSource = validCommit(revision) ? "OCI 镜像标签" : (gitCommit == null ? null : "镜像标签 sha-*");
        String version = clean(label(labels, "org.opencontainers.image.version"));
        if (version == null && tag.matches("^v?[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$"))
            version = tag.replaceFirst("^v", "");
        Instant createdAt = parseInstant(config.path("created").asText(null));
        return new ImageInfo(tag, digest, createdAt, gitCommit, gitSource, version, architecture, size);
    }

    private HttpResponse<String> request(URI uri, Models.ImageRegistryConfig registry, String accept)
            throws Exception {
        HttpResponse<String> response = send(uri, registry, null, accept);
        if (response.statusCode() == 401) {
            String challenge = response.headers().firstValue("WWW-Authenticate").orElse("");
            if (!challenge.regionMatches(true, 0, "Bearer ", 0, 7)) throw registryFailure(response);
            String token = bearerToken(challenge, registry);
            response = send(uri, registry, "Bearer " + token, accept);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw registryFailure(response);
        return response;
    }

    private HttpResponse<String> send(URI uri, Models.ImageRegistryConfig registry, String authorization,
                                      String accept) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("Accept", accept).GET();
        if (authorization != null) request.header("Authorization", authorization);
        else if ("BASIC".equals(registry.authType)) request.header("Authorization", basicAuthorization(registry));
        return http().send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String bearerToken(String challenge, Models.ImageRegistryConfig registry) throws Exception {
        Map<String, String> parameters = new LinkedHashMap<>();
        Matcher matcher = CHALLENGE_PARAMETER.matcher(challenge.substring(7));
        while (matcher.find()) parameters.put(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2));
        String realm = parameters.get("realm");
        if (realm == null || realm.isBlank()) throw new IllegalStateException("Registry 返回的 Bearer 认证信息缺少 realm");
        String scope = parameters.getOrDefault("scope", "repository:" + registry.repository + ":pull");
        String separator = realm.contains("?") ? "&" : "?";
        String tokenUrl = realm + separator + "service=" + encode(parameters.getOrDefault("service", ""))
                + "&scope=" + encode(scope);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(tokenUrl)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json").GET();
        if ("BASIC".equals(registry.authType)) request.header("Authorization", basicAuthorization(registry));
        HttpResponse<String> response = http().send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw registryFailure(response);
        JsonNode body = objectMapper.readTree(response.body());
        String token = body.path("token").asText(body.path("access_token").asText(""));
        if (token.isBlank()) throw new IllegalStateException("Registry 认证服务没有返回访问令牌");
        return token;
    }

    private String basicAuthorization(Models.ImageRegistryConfig registry) {
        String value = registry.username + ":" + crypto.decrypt(registry.secretCipher);
        return "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 延迟创建，保证未使用 Registry 浏览功能的纯离线启动不会初始化网络选择器。 */
    private HttpClient http() {
        HttpClient value = http;
        if (value != null) return value;
        synchronized (this) {
            if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            return http;
        }
    }

    private IllegalStateException registryFailure(HttpResponse<String> response) {
        String detail = response.body() == null ? "" : response.body().replaceAll("\\s+", " ").trim();
        if (detail.length() > 400) detail = detail.substring(0, 400) + "…";
        return new IllegalStateException("Registry 请求失败（HTTP " + response.statusCode() + "）"
                + (detail.isBlank() ? "" : "：" + detail));
    }

    private Models.ImageRegistryConfig config(Models.Project project, String registryId) {
        return project.imageRegistries.stream().filter(value -> registryId.equals(value.id)).findFirst()
                .orElseThrow(() -> new PlatformStore.NotFoundException("镜像仓库绑定不存在：" + registryId));
    }

    private String normalizeRole(String role) {
        String value = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!List.of("FRONTEND", "BACKEND").contains(value))
            throw new IllegalArgumentException("应用角色只支持 FRONTEND 或 BACKEND");
        return value;
    }

    private String normalizeRegistryUrl(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Registry 地址不能为空");
        String candidate = value.trim();
        if (!candidate.contains("://")) candidate = "https://" + candidate;
        URI uri;
        try { uri = URI.create(candidate); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Registry 地址格式不正确"); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!List.of("http", "https").contains(scheme) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath())))
            throw new IllegalArgumentException("Registry 地址必须是 http(s)://主机[:端口]，镜像路径请单独填写");
        return scheme + "://" + uri.getRawAuthority();
    }

    private String normalizePullAuthority(String value, String registryUrl) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isBlank()) return URI.create(registryUrl).getRawAuthority();
        try {
            URI uri = URI.create("http://" + candidate);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isBlank()))
                throw new IllegalArgumentException();
            return uri.getRawAuthority();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Registry 拉取地址必须是主机[:端口]");
        }
    }

    private String normalizeRepository(String value) {
        String repository = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (repository.length() > 255 || !REPOSITORY.matcher(repository).matches())
            throw new IllegalArgumentException("镜像路径格式不正确，例如 team/app-backend");
        return repository;
    }

    private URI manifestUri(Models.ImageRegistryConfig registry, String reference) {
        return URI.create(registry.registryUrl + "/v2/" + registry.repository + "/manifests/" + reference);
    }

    private URI blobUri(Models.ImageRegistryConfig registry, String digest) {
        return URI.create(registry.registryUrl + "/v2/" + registry.repository + "/blobs/" + digest);
    }

    private String label(JsonNode labels, String key) {
        return labels.isObject() ? labels.path(key).asText(null) : null;
    }

    private String commitFromTag(String tag) {
        Matcher matcher = TAG_COMMIT.matcher(tag);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private boolean validCommit(String value) {
        return value != null && HEX_COMMIT.matcher(value.trim()).matches();
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); }
        catch (Exception ignored) {
            try { return OffsetDateTime.parse(value).toInstant(); }
            catch (Exception ignoredAgain) { return null; }
        }
    }

    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record ImageCatalog(List<String> tags, List<ImageInfo> images,
                               List<UnavailableImage> unavailableTags) {}

    public record ImageInfo(String tag, String digest, Instant createdAt, String gitCommit,
                            String gitSource, String version, String architecture, long size) {}

    public record UnavailableImage(String tag, String reason) {}

    public static final class RegistryInput {
        public String role;
        public String registryUrl;
        public String repository;
        public String authType;
        public String username;
        public String secret;
    }
}
