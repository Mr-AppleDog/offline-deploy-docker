package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.catalog.CatalogEntry;
import com.example.offlinedemo.platform.catalog.MiddlewareCatalog;
import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.security.CryptoService;
import com.example.offlinedemo.platform.store.PlatformStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProfileService {
    private final PlatformStore store;
    private final CryptoService crypto;
    private final MiddlewareCatalog catalog;

    public ProfileService(PlatformStore store, CryptoService crypto, MiddlewareCatalog catalog) {
        this.store = store;
        this.crypto = crypto;
        this.catalog = catalog;
    }

    public Models.DeploymentProfile save(String id, ProfileInput input) {
        if (input.name == null || input.name.isBlank()) throw new IllegalArgumentException("配置名称不能为空");
        if (input.projectId == null || input.projectId.isBlank()) throw new IllegalArgumentException("部署配置必须绑定项目");
        Models.Project project = store.project(input.projectId);
        Instant now = Instant.now();
        boolean creating = id == null;
        Models.DeploymentProfile profile;
        if (creating) {
            profile = new Models.DeploymentProfile();
            profile.id = UUID.randomUUID().toString();
            profile.createdAt = now;
            profile.revision = 0;
        } else {
            profile = store.profile(id);
        }

        if (!creating && profile.projectId != null && !profile.projectId.isBlank()
                && !profile.projectId.equals(project.id))
            throw new IllegalArgumentException("部署配置所属项目创建后不可修改，请为目标项目新建配置");
        profile.projectId = project.id;
        profile.name = input.name.trim();
        profile.environment = defaultValue(input.environment, "生产环境");
        String deployedVersion = defaultValue(input.deployedVersion, project.currentVersion);
        requireVersion(deployedVersion, "已部署版本");
        profile.deployedVersion = deployedVersion;
        Models.BuildTarget target = Models.BuildTarget.of(project.targetOs, project.targetArch).normalized();
        profile.targetOs = target.os;
        profile.targetArch = target.arch;
        profile.frontendPort = input.frontendPort == null ? 80 : input.frontendPort;
        if (profile.frontendPort < 1 || profile.frontendPort > 65535) throw new IllegalArgumentException("前端端口无效");
        profile.timezone = defaultValue(input.timezone, "Asia/Shanghai");
        profile.javaOptions = defaultValue(input.javaOptions, "-Xms256m -Xmx1024m");

        Map<String, Map<String, String>> existing = new LinkedHashMap<>();
        for (Models.MiddlewareCredential mc : profile.middleware) existing.put(mc.component, mc.values);

        List<Models.MiddlewareCredential> next = new ArrayList<>();
        if (input.middleware != null) {
            for (ProfileInput.MiddlewareInput mi : input.middleware) {
                CatalogEntry entry = catalog.entry(mi.component);
                Models.MiddlewareCredential mc = new Models.MiddlewareCredential();
                mc.component = mi.component;
                Map<String, String> old = existing.getOrDefault(mi.component, Map.of());
                Map<String, String> incoming = mi.credentials == null ? Map.of() : mi.credentials;
                for (CatalogEntry.Credential cred : entry.credentials) {
                    String provided = incoming.get(cred.key);
                    String current = old.get(cred.key);
                    if (cred.secret) {
                        mc.values.put(cred.key, secret(provided, current, creating && cred.required, cred.label));
                    } else {
                        String value = defaultValue(provided, cred.defaultValue);
                        if (cred.required && creating && (value == null || value.isBlank()))
                            throw new IllegalArgumentException(cred.label + "不能为空");
                        mc.values.put(cred.key, value == null ? "" : value);
                    }
                }
                next.add(mc);
            }
        }
        profile.middleware = next;
        profile.revision++;
        profile.updatedAt = now;
        store.putProfile(profile);
        return profile;
    }

    public ResolvedProfile resolve(String id) {
        Models.DeploymentProfile p = store.profile(id);
        Map<String, Map<String, String>> resolved = new LinkedHashMap<>();
        for (Models.MiddlewareCredential mc : p.middleware) {
            CatalogEntry entry = catalog.entry(mc.component);
            Map<String, String> values = new LinkedHashMap<>();
            for (CatalogEntry.Credential cred : entry.credentials) {
                String stored = mc.values.getOrDefault(cred.key, "");
                values.put(cred.key, cred.secret ? crypto.decrypt(stored) : stored);
            }
            resolved.put(mc.component, values);
        }
        return new ResolvedProfile(p, resolved);
    }

    public String generatePassword() { return crypto.generatePassword(); }

    private String secret(String candidate, String current, boolean required, String label) {
        if (candidate == null || candidate.isBlank()) {
            if (required && (current == null || current.isBlank())) throw new IllegalArgumentException(label + "不能为空");
            return current;
        }
        if (candidate.length() < 12) throw new IllegalArgumentException(label + "至少 12 个字符");
        if (candidate.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(label + "不能包含控制字符");
        if (candidate.contains("$") || candidate.contains("\\") || candidate.contains("\"") || candidate.contains("'"))
            throw new IllegalArgumentException(label + "不能包含 $、反斜杠或引号；可使用页面生成的安全密码");
        return crypto.encrypt(candidate);
    }

    private String defaultValue(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private void requireVersion(String value, String label) {
        if (value == null || !value.matches("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$"))
            throw new IllegalArgumentException(label + "必须使用语义化版本，例如 1.2.3");
    }

    public static final class ProfileInput {
        public String projectId;
        public String name;
        public String environment;
        public String deployedVersion;
        public String targetOs;
        public String targetArch;
        public Integer frontendPort;
        public String timezone;
        public String javaOptions;
        public List<MiddlewareInput> middleware;

        public static final class MiddlewareInput {
            public String component;
            public Map<String, String> credentials = new LinkedHashMap<>();
        }
    }

    /** 已解密（secret 字段）的部署配置快照。credentials 键为 component -> credentialKey -> 明文。 */
    public record ResolvedProfile(Models.DeploymentProfile profile, Map<String, Map<String, String>> credentials) {
        public List<CatalogEntry> selectedEntries(MiddlewareCatalog catalog) {
            return profile.middleware.stream().map(mc -> catalog.entry(mc.component)).toList();
        }

        public String value(String component, String key) {
            Map<String, String> map = credentials.get(component);
            return map == null ? "" : map.getOrDefault(key, "");
        }
    }
}
