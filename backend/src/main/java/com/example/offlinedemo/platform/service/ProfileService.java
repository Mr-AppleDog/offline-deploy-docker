package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.security.CryptoService;
import com.example.offlinedemo.platform.store.PlatformStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class ProfileService {
    private final PlatformStore store;
    private final CryptoService crypto;

    public ProfileService(PlatformStore store, CryptoService crypto) {
        this.store = store;
        this.crypto = crypto;
    }

    public Models.DeploymentProfile save(String id, ProfileInput input) {
        if (input.name == null || input.name.isBlank()) throw new IllegalArgumentException("配置名称不能为空");
        Instant now = Instant.now();
        boolean creating = id == null;
        Models.DeploymentProfile profile;
        if (creating) {
            profile = new Models.DeploymentProfile();
            profile.id = UUID.randomUUID().toString();
            profile.createdAt = now;
            profile.revision = 0;
        } else profile = store.profile(id);

        profile.name = input.name.trim();
        profile.environment = defaultValue(input.environment, "生产环境");
        profile.mysqlDatabase = identifier(defaultValue(input.mysqlDatabase, "kunlun_app"), "MySQL 数据库名");
        profile.mysqlRootUsername = identifier(defaultValue(input.mysqlRootUsername, "root"), "MySQL root 账号");
        profile.mysqlUsername = identifier(defaultValue(input.mysqlUsername, "kunlun_app"), "MySQL 业务账号");
        profile.redisDatabase = input.redisDatabase == null ? 0 : input.redisDatabase;
        if (profile.redisDatabase < 0 || profile.redisDatabase > 15) throw new IllegalArgumentException("Redis DB 必须在 0-15 之间");
        profile.rabbitmqUsername = identifier(defaultValue(input.rabbitmqUsername, "kunlun_app"), "RabbitMQ 账号");
        profile.rabbitmqVhost = defaultValue(input.rabbitmqVhost, "/");
        profile.minioAccessKey = identifier(defaultValue(input.minioAccessKey, "kunlunadmin"), "MinIO Access Key");
        profile.minioBucket = bucket(defaultValue(input.minioBucket, "kunlun-app"));
        profile.frontendPort = input.frontendPort == null ? 80 : input.frontendPort;
        if (profile.frontendPort < 1 || profile.frontendPort > 65535) throw new IllegalArgumentException("前端端口无效");
        profile.timezone = defaultValue(input.timezone, "Asia/Shanghai");
        profile.javaOptions = defaultValue(input.javaOptions, "-Xms256m -Xmx1024m");

        profile.mysqlRootPasswordCipher = secret(input.mysqlRootPassword, profile.mysqlRootPasswordCipher, creating, "MySQL root 密码");
        profile.mysqlPasswordCipher = secret(input.mysqlPassword, profile.mysqlPasswordCipher, creating, "MySQL 业务密码");
        profile.redisPasswordCipher = secret(input.redisPassword, profile.redisPasswordCipher, creating, "Redis 密码");
        profile.rabbitmqPasswordCipher = secret(input.rabbitmqPassword, profile.rabbitmqPasswordCipher, creating, "RabbitMQ 密码");
        profile.minioSecretKeyCipher = secret(input.minioSecretKey, profile.minioSecretKeyCipher, creating, "MinIO Secret Key");
        profile.revision++;
        profile.updatedAt = now;
        store.putProfile(profile);
        return profile;
    }

    public ResolvedProfile resolve(String id) {
        Models.DeploymentProfile p = store.profile(id);
        return new ResolvedProfile(p, crypto.decrypt(p.mysqlRootPasswordCipher), crypto.decrypt(p.mysqlPasswordCipher),
                crypto.decrypt(p.redisPasswordCipher), crypto.decrypt(p.rabbitmqPasswordCipher),
                crypto.decrypt(p.minioSecretKeyCipher));
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

    private String identifier(String value, String label) {
        if (!value.matches("^[A-Za-z][A-Za-z0-9_.-]{1,62}$")) throw new IllegalArgumentException(label + "格式不正确");
        return value;
    }

    private String bucket(String value) {
        if (!value.matches("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")) throw new IllegalArgumentException("MinIO Bucket 格式不正确");
        return value;
    }

    private String defaultValue(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }

    public static final class ProfileInput {
        public String name;
        public String environment;
        public String mysqlDatabase;
        public String mysqlRootUsername;
        public String mysqlRootPassword;
        public String mysqlUsername;
        public String mysqlPassword;
        public Integer redisDatabase;
        public String redisPassword;
        public String rabbitmqUsername;
        public String rabbitmqPassword;
        public String rabbitmqVhost;
        public String minioAccessKey;
        public String minioSecretKey;
        public String minioBucket;
        public Integer frontendPort;
        public String timezone;
        public String javaOptions;
    }

    public record ResolvedProfile(Models.DeploymentProfile profile, String mysqlRootPassword,
                                  String mysqlPassword, String redisPassword,
                                  String rabbitmqPassword, String minioSecretKey) {}
}
