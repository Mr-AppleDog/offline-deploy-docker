package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.catalog.CatalogEntry;
import com.example.offlinedemo.platform.domain.Models;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按中间件注册表（CatalogEntry）渲染两份 compose 文件。
 * 版本来自制品，凭据来自已解密的 ResolvedProfile，平台来自 BuildTarget。
 */
@Service
public class ComposeRenderer {
    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    public String middleware(ProfileService.ResolvedProfile resolved, List<CatalogEntry> selected,
                             Map<String, String> versions, Models.BuildTarget target) {
        StringBuilder sb = new StringBuilder();
        sb.append("name: kunlun-middleware\n\n");
        sb.append("# 由离线交付平台生成。该文件含站点专属凭据，必须以 0600 保存。\n");
        appendAnchors(sb, resolved, selected);
        sb.append("\nx-common: &common\n");
        appendCommon(sb, target.ociPlatform());
        sb.append("\nservices:\n");
        for (CatalogEntry entry : selected) {
            appendService(sb, entry, resolved, version(versions, entry.component));
        }
        appendNetworks(sb);
        return sb.toString();
    }

    public String application(ProfileService.ResolvedProfile resolved, String appKey,
                              String backendVersion, String frontendVersion,
                              String backendHealthPath, String frontendHealthPath,
                              List<CatalogEntry> selected, java.util.Set<String> includedApps,
                              Models.BuildTarget target) {
        StringBuilder sb = new StringBuilder();
        sb.append("name: kunlun-app\n\n");
        sb.append("# 由离线交付平台生成。必须与在线 middleware Compose 的凭据一致。\n");
        appendAnchors(sb, resolved, selected);
        sb.append("\nx-common: &common\n");
        appendCommon(sb, target.ociPlatform());
        sb.append("\nservices:\n");
        appendAppService(sb, resolved, selected, appKey, backendVersion, frontendVersion,
                backendHealthPath, frontendHealthPath, includedApps);
        appendNetworks(sb);
        return sb.toString();
    }

    private void appendAnchors(StringBuilder sb, ProfileService.ResolvedProfile resolved,
                               List<CatalogEntry> selected) {
        for (CatalogEntry entry : selected) {
            for (CatalogEntry.Credential cred : entry.credentials) {
                if (!cred.anchor) continue;
                sb.append(xKey(entry.component, cred.key)).append(": &").append(anchor(entry.component, cred.key))
                        .append(' ').append(yaml(resolved.value(entry.component, cred.key))).append('\n');
            }
        }
    }

    private void appendCommon(StringBuilder sb, String platform) {
        sb.append("  platform: ").append(platform).append('\n')
                .append("  pull_policy: never\n")
                .append("  restart: unless-stopped\n")
                .append("  networks: [kunlun-net]\n")
                .append("  logging:\n")
                .append("    driver: local\n")
                .append("    options: { max-size: 100m, max-file: \"3\" }\n");
    }

    private void appendService(StringBuilder sb, CatalogEntry entry, ProfileService.ResolvedProfile resolved,
                               String version) {
        sb.append("  ").append(entry.component).append(":\n");
        sb.append("    <<: *common\n");
        sb.append("    image: ").append(entry.imageRepo).append(':').append(version).append('\n');
        if (entry.extraService.hostname != null && !entry.extraService.hostname.isBlank())
            sb.append("    hostname: ").append(entry.extraService.hostname).append('\n');
        if (entry.ports != null && !entry.ports.isBlank())
            sb.append("    ports: ").append(entry.ports).append('\n');
        sb.append("    environment:\n");
        for (CatalogEntry.Credential cred : entry.credentials) {
            if (cred.envVar == null || cred.envVar.isBlank()) continue;
            String value = resolved.value(entry.component, cred.key);
            if (cred.anchor) sb.append("      ").append(cred.envVar).append(": *").append(anchor(entry.component, cred.key)).append('\n');
            else sb.append("      ").append(cred.envVar).append(": ").append(yaml(value)).append('\n');
        }
        sb.append("      TZ: ").append(yaml(resolved.profile().timezone)).append('\n');
        if (entry.extraService.command != null && !entry.extraService.command.isBlank())
            sb.append("    command: ").append(entry.extraService.command).append('\n');
        if (!entry.volumes.isEmpty()) {
            sb.append("    volumes:\n");
            for (CatalogEntry.Volume volume : entry.volumes) {
                sb.append("      - /opt/Kunlun/middleware/").append(entry.component).append('/').append(volume.dir)
                        .append(':').append(volume.container).append(volume.flags).append('\n');
            }
        }
        sb.append("    healthcheck:\n")
                .append("      test: ").append(entry.healthcheck.test).append('\n')
                .append("      interval: ").append(entry.healthcheck.interval).append('\n')
                .append("      timeout: ").append(entry.healthcheck.timeout).append('\n')
                .append("      retries: ").append(entry.healthcheck.retries).append('\n');
        if (entry.healthcheck.startPeriod != null && !entry.healthcheck.startPeriod.isBlank())
            sb.append("      start_period: ").append(entry.healthcheck.startPeriod).append('\n');
        sb.append('\n');
    }

    private void appendAppService(StringBuilder sb, ProfileService.ResolvedProfile resolved,
                                  List<CatalogEntry> selected, String appKey, String backendVersion,
                                  String frontendVersion, String backendHealthPath, String frontendHealthPath,
                                  java.util.Set<String> includedApps) {
        boolean hasBackend = includedApps != null && includedApps.contains("app-backend");
        boolean hasFrontend = includedApps != null && includedApps.contains("app-frontend");
        if (hasBackend) {
            sb.append("  backend:\n")
                    .append("    <<: *common\n")
                    .append("    image: ").append(appKey).append("-backend:").append(backendVersion).append('\n')
                    .append("    environment:\n");
            for (CatalogEntry entry : selected) {
                for (CatalogEntry.EnvEntry conn : entry.appConnections) {
                    appendAppEnv(sb, entry, conn, resolved);
                }
            }
            sb.append("      JAVA_TOOL_OPTIONS: ").append(yaml(resolved.profile().javaOptions)).append('\n')
                    .append("      TZ: ").append(yaml(resolved.profile().timezone)).append('\n')
                    .append("    healthcheck:\n")
                    .append("      test: [\"CMD\", \"wget\", \"-q\", \"-O\", \"/dev/null\", ")
                    .append(yaml("http://127.0.0.1:8080" + backendHealthPath)).append("]\n")
                    .append("      interval: 10s\n")
                    .append("      timeout: 5s\n")
                    .append("      retries: 18\n")
                    .append("      start_period: 30s\n\n");
        }
        if (hasFrontend) {
            sb.append("  frontend:\n")
                    .append("    <<: *common\n")
                    .append("    image: ").append(appKey).append("-frontend:").append(frontendVersion).append('\n');
            if (hasBackend) sb.append("    depends_on:\n      backend: { condition: service_healthy }\n");
            sb.append("    ports: [").append(resolved.profile().frontendPort).append(":80]\n")
                    .append("    healthcheck:\n")
                    .append("      test: [\"CMD\", \"wget\", \"-q\", \"-O\", \"/dev/null\", ")
                    .append(yaml("http://127.0.0.1" + frontendHealthPath)).append("]\n")
                    .append("      interval: 10s\n")
                    .append("      timeout: 5s\n")
                    .append("      retries: 12\n\n");
        }
    }

    private void appendAppEnv(StringBuilder sb, CatalogEntry entry, CatalogEntry.EnvEntry conn,
                              ProfileService.ResolvedProfile resolved) {
        sb.append("      ").append(conn.env).append(": ");
        if (conn.literal != null) {
            sb.append(conn.literal);
        } else if (conn.template != null) {
            sb.append(yaml(resolveTemplate(conn.template, entry, resolved)));
        } else if (conn.credential != null) {
            CatalogEntry.Credential cred = entry.credential(conn.credential);
            if (cred != null && cred.anchor) {
                sb.append('*').append(anchor(entry.component, conn.credential));
            } else {
                String value = resolved.value(entry.component, conn.credential);
                sb.append(conn.quote ? yaml(value) : value);
            }
        }
        sb.append('\n');
    }

    private void appendNetworks(StringBuilder sb) {
        sb.append("networks:\n")
                .append("  kunlun-net:\n")
                .append("    external: true\n")
                .append("    name: kunlun-net\n");
    }

    private String resolveTemplate(String template, CatalogEntry entry, ProfileService.ResolvedProfile resolved) {
        Matcher matcher = TEMPLATE.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = "timezone".equals(key) ? resolved.profile().timezone : resolved.value(entry.component, key);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String version(Map<String, String> values, String component) {
        String value = values.get(component);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少 " + component + " 制品版本");
        return value;
    }

    private String anchor(String component, String key) { return "kunlun-" + component + "-" + key; }
    private String xKey(String component, String key) { return "x-kunlun-" + component + "-" + key; }

    private String yaml(String value) {
        if (value == null) value = "";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }
}