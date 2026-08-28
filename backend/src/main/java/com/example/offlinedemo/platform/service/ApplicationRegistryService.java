package com.example.offlinedemo.platform.service;

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

/** 管理项目的前后端 Docker Registry 绑定，并通过 Registry V2 API 浏览标签。 */
@Service
public class ApplicationRegistryService {
    private static final Pattern REPOSITORY = Pattern.compile(
            "^[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*$");
    private static final Pattern CHALLENGE_PARAMETER = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    private final PlatformStore store;
    private final CryptoService crypto;
    private final ObjectMapper objectMapper;
    private volatile HttpClient http;

    public ApplicationRegistryService(PlatformStore store, CryptoService crypto, ObjectMapper objectMapper) {
        this.store = store;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
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

    public List<String> tags(String projectId, String registryId) {
        Models.ImageRegistryConfig registry = config(projectId, registryId);
        URI uri = URI.create(registry.registryUrl + "/v2/" + registry.repository + "/tags/list?n=200");
        try {
            HttpResponse<String> response = send(uri, registry, null);
            if (response.statusCode() == 401) {
                String challenge = response.headers().firstValue("WWW-Authenticate").orElse("");
                if (!challenge.regionMatches(true, 0, "Bearer ", 0, 7))
                    throw registryFailure(response);
                String token = bearerToken(challenge, registry);
                response = send(uri, registry, "Bearer " + token);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw registryFailure(response);
            JsonNode tagsNode = objectMapper.readTree(response.body()).path("tags");
            List<String> tags = new ArrayList<>();
            if (tagsNode.isArray()) tagsNode.forEach(value -> { if (value.isTextual() && !value.asText().isBlank()) tags.add(value.asText()); });
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

    public Map<String, Object> view(Models.ImageRegistryConfig registry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", registry.id);
        view.put("role", registry.role);
        view.put("registryUrl", registry.registryUrl);
        view.put("repository", registry.repository);
        view.put("authType", registry.authType);
        view.put("username", registry.username == null ? "" : registry.username);
        view.put("credentialConfigured", registry.secretCipher != null && !registry.secretCipher.isBlank());
        view.put("updatedAt", registry.updatedAt);
        return view;
    }

    public static String imageReference(Models.ImageRegistryConfig registry, String tag) {
        String authority = URI.create(registry.registryUrl).getRawAuthority();
        return authority + "/" + registry.repository + ":" + tag;
    }

    public static String loginServer(Models.ImageRegistryConfig registry) {
        return URI.create(registry.registryUrl).getRawAuthority();
    }

    private HttpResponse<String> send(URI uri, Models.ImageRegistryConfig registry, String authorization)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").GET();
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
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(tokenUrl)).timeout(Duration.ofSeconds(30))
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
            if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
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

    private String normalizeRepository(String value) {
        String repository = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (repository.length() > 255 || !REPOSITORY.matcher(repository).matches())
            throw new IllegalArgumentException("镜像路径格式不正确，例如 team/app-backend");
        return repository;
    }

    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public static final class RegistryInput {
        public String role;
        public String registryUrl;
        public String repository;
        public String authType;
        public String username;
        public String secret;
    }
}
