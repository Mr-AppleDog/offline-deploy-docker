package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.security.CryptoService;
import com.example.offlinedemo.platform.store.PlatformStore;
import com.example.offlinedemo.platform.util.CommandRunner;
import com.example.offlinedemo.platform.util.FileSupport;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class RepositoryService {
    private final PlatformStore store;
    private final CryptoService crypto;
    private final CommandRunner commands;

    public RepositoryService(PlatformStore store, CryptoService crypto, CommandRunner commands) {
        this.store = store;
        this.crypto = crypto;
        this.commands = commands;
    }

    public List<RepositorySnapshot> checkout(Models.Project project, String workspaceName,
                                              Consumer<String> logger) throws Exception {
        if (project.repositories == null || project.repositories.isEmpty()) {
            throw new IllegalArgumentException("项目还没有配置代码仓库");
        }
        Path workspace = FileSupport.safeResolve(store.workspacesRoot(), workspaceName, "工作目录");
        if (Files.exists(workspace)) FileSupport.deleteTree(store.workspacesRoot(), workspace);
        Files.createDirectories(workspace);
        List<RepositorySnapshot> result = new ArrayList<>();
        try {
            for (Models.RepositoryConfig repository : project.repositories) {
                result.add(cloneOne(repository, workspace, logger));
            }
            return result;
        } catch (Exception e) {
            try { FileSupport.deleteTree(store.workspacesRoot(), workspace); } catch (IOException ignored) {}
            throw e;
        }
    }

    /**
     * 轻量读取项目指定角色仓库的远端 commit，不克隆工作树。优先解析配置的分支，
     * 也支持 HEAD、完整 refs/heads/* 与 refs/tags/*（附注标签取解引用后的 commit）。
     */
    public ResolvedCommit resolveCommit(String projectId, String role) throws Exception {
        Models.Project project = store.project(projectId);
        String normalizedRole = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!List.of("FRONTEND", "BACKEND").contains(normalizedRole))
            throw new IllegalArgumentException("仓库角色只支持 FRONTEND 或 BACKEND");
        Models.RepositoryConfig repository = project.repositories.stream()
                .filter(value -> normalizedRole.equals(value.role)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请先为项目绑定 " + normalizedRole + " Git 仓库"));
        if (repository.url == null || repository.url.isBlank() || repository.url.startsWith("-"))
            throw new IllegalArgumentException("仓库地址无效：" + normalizedRole);

        String requestedRef = defaultValue(repository.ref, "HEAD");
        String normalizedRef = defaultRef(requestedRef);
        if (normalizedRef.matches("^[0-9a-fA-F]{40}(?:[0-9a-fA-F]{24})?$"))
            return new ResolvedCommit(normalizedRole, repository.id, repository.url, requestedRef,
                    normalizedRef.toLowerCase(Locale.ROOT));

        Path workspace = FileSupport.safeResolve(store.workspacesRoot(),
                "git-resolve/" + project.id + "-" + UUID.randomUUID(), "Git Commit 查询目录");
        Map<String, String> environment = Map.of();
        try {
            Files.createDirectories(workspace);
            environment = credentialEnvironment(repository, workspace, normalizedRole.toLowerCase(Locale.ROOT));
            List<String> command = new ArrayList<>(List.of("git", "ls-remote", "--exit-code", "--", repository.url));
            command.addAll(refPatterns(requestedRef));
            String output = commands.run(command, workspace, environment, null).output();
            String commit = selectCommit(output, requestedRef);
            if (commit == null)
                throw new IllegalArgumentException("仓库中找不到引用 " + requestedRef + "：" + repository.url);
            return new ResolvedCommit(normalizedRole, repository.id, repository.url, requestedRef, commit);
        } finally {
            cleanupCredentialFiles(environment);
            try { FileSupport.deleteTree(store.workspacesRoot(), workspace); } catch (IOException ignored) {}
        }
    }

    static String selectCommit(String output, String requestedRef) {
        Map<String, String> refs = new LinkedHashMap<>();
        if (output != null) {
            for (String line : output.lines().toList()) {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length == 2 && parts[0].matches("^[0-9a-fA-F]{40}(?:[0-9a-fA-F]{24})?$"))
                    refs.put(parts[1], parts[0].toLowerCase(Locale.ROOT));
            }
        }
        String value = defaultRef(requestedRef);
        List<String> preferred;
        if ("HEAD".equalsIgnoreCase(value)) preferred = List.of("HEAD");
        else if (value.startsWith("refs/tags/")) preferred = List.of(value + "^{}", value);
        else if (value.startsWith("refs/")) preferred = List.of(value);
        else preferred = List.of("refs/heads/" + value, "refs/tags/" + value + "^{}", "refs/tags/" + value, value);
        for (String ref : preferred) if (refs.containsKey(ref)) return refs.get(ref);
        return null;
    }

    private static List<String> refPatterns(String requestedRef) {
        String value = defaultRef(requestedRef);
        if ("HEAD".equalsIgnoreCase(value)) return List.of("HEAD");
        if (value.startsWith("refs/tags/")) return List.of(value, value + "^{}");
        if (value.startsWith("refs/")) return List.of(value);
        return List.of(value, "refs/heads/" + value, "refs/tags/" + value, "refs/tags/" + value + "^{}");
    }

    private static String defaultRef(String value) {
        String ref = value == null || value.isBlank() ? "HEAD" : value.trim();
        if (ref.startsWith("refs/remotes/origin/")) return ref.substring("refs/remotes/origin/".length());
        return ref.startsWith("origin/") ? ref.substring("origin/".length()) : ref;
    }

    private RepositorySnapshot cloneOne(Models.RepositoryConfig repository, Path workspace,
                                        Consumer<String> logger) throws Exception {
        if (repository.url == null || repository.url.isBlank() || repository.url.startsWith("-")) {
            throw new IllegalArgumentException("仓库地址无效：" + repository.role);
        }
        String safeRole = repository.role.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        Path target = workspace.resolve(safeRole).normalize();
        if (!target.startsWith(workspace)) throw new IllegalArgumentException("仓库角色目录非法");
        Map<String, String> environment = credentialEnvironment(repository, workspace, safeRole);
        try {
            logger.accept("拉取 " + repository.role + " 仓库，目标引用：" + defaultValue(repository.ref, "HEAD"));
            // --no-local 避免本地仓库克隆使用硬链接，把源仓库 .git 的只读 ACL 带入工作目录。
            cloneWithRetry(repository, target, workspace, environment, logger);

            String requestedRef = defaultValue(repository.ref, "HEAD");
            String resolvedRef = requestedRef;
            try {
                commands.run(List.of("git", "-C", target.toString(), "rev-parse", "--verify", requestedRef + "^{commit}"),
                        workspace, environment, null);
            } catch (CommandRunner.CommandFailedException first) {
                resolvedRef = "origin/" + requestedRef;
                commands.run(List.of("git", "-C", target.toString(), "rev-parse", "--verify", resolvedRef + "^{commit}"),
                        workspace, environment, null);
            }
            commands.run(List.of("git", "-C", target.toString(), "checkout", "--detach", resolvedRef),
                    workspace, environment, logger);
            String commit = commands.run(List.of("git", "-C", target.toString(), "rev-parse", "HEAD"),
                    workspace, environment, null).output().trim();

            Path context = FileSupport.safeResolve(target, defaultValue(repository.subdirectory, "."), "仓库子目录");
            if (!Files.isDirectory(context)) throw new IllegalArgumentException("仓库子目录不存在：" + repository.subdirectory);
            logger.accept(repository.role + " 已锁定 Commit " + commit.substring(0, Math.min(12, commit.length())));
            return new RepositorySnapshot(repository.role, target, context, commit, repository.dockerfile);
        } finally {
            cleanupCredentialFiles(environment);
        }
    }

    /**
     * 克隆仓库，针对 GitHub HTTPS 偶发“Connection reset by peer”自动重试。
     * 每次重试前清空目标目录残留，避免 git 报“destination path already exists”。
     */
    private void cloneWithRetry(Models.RepositoryConfig repository, Path target, Path workspace,
                                Map<String, String> environment, Consumer<String> logger) throws Exception {
        List<String> command = List.of("git", "clone", "--no-checkout", "--no-local", "--",
                repository.url, target.toString());
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                commands.run(command, workspace, environment, logger);
                return;
            } catch (IOException failure) {
                if (attempt >= maxAttempts) throw failure;
                try { FileSupport.deleteTree(workspace, target); } catch (IOException ignored) {}
                long backoffMillis = attempt * 1000L;
                logger.accept(repository.role + " 仓库克隆失败（第 " + attempt + "/" + maxAttempts + " 次），"
                        + backoffMillis + "ms 后重试：" + firstLine(failure.getMessage()));
                Thread.sleep(backoffMillis);
            }
        }
    }

    private String firstLine(String message) {
        if (message == null) return "";
        int newline = message.indexOf(System.lineSeparator());
        return newline < 0 ? message : message.substring(0, newline);
    }

    private Map<String, String> credentialEnvironment(Models.RepositoryConfig repository, Path workspace,
                                                       String safeRole) throws IOException {
        Map<String, String> environment = new HashMap<>();
        environment.put("GIT_TERMINAL_PROMPT", "0");
        if ("HTTPS".equalsIgnoreCase(repository.authType)) {
            String secret = crypto.decrypt(repository.secretCipher);
            if (secret.isBlank()) throw new IllegalArgumentException("HTTPS 仓库没有配置 Token/密码");
            Path askPass;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                askPass = workspace.resolve("askpass-" + safeRole + ".cmd");
                Files.writeString(askPass,
                        "@echo off\r\npowershell -NoProfile -Command \"if ($args[0] -match 'Username') { [Console]::Out.Write($env:GIT_KUNLUN_USERNAME) } else { [Console]::Out.Write($env:GIT_KUNLUN_SECRET) }\" %1\r\n",
                        StandardCharsets.US_ASCII);
            } else {
                askPass = workspace.resolve("askpass-" + safeRole + ".sh");
                Files.writeString(askPass,
                        "#!/usr/bin/env sh\ncase \"$1\" in *Username*) printf '%s' \"$GIT_KUNLUN_USERNAME\" ;; *) printf '%s' \"$GIT_KUNLUN_SECRET\" ;; esac\n",
                        StandardCharsets.US_ASCII);
                askPass.toFile().setExecutable(true, true);
            }
            environment.put("GIT_ASKPASS", askPass.toString());
            environment.put("GIT_KUNLUN_USERNAME", defaultValue(repository.username, "oauth2"));
            environment.put("GIT_KUNLUN_SECRET", secret);
            environment.put("KUNLUN_ASKPASS_FILE", askPass.toString());
        } else if ("SSH".equalsIgnoreCase(repository.authType)) {
            String privateKey = crypto.decrypt(repository.secretCipher);
            if (privateKey.isBlank()) throw new IllegalArgumentException("SSH 仓库没有配置私钥");
            Path keyFile = workspace.resolve("ssh-key-" + safeRole);
            Files.writeString(keyFile, privateKey, StandardCharsets.UTF_8);
            keyFile.toFile().setReadable(false, false);
            keyFile.toFile().setReadable(true, true);
            environment.put("GIT_SSH_COMMAND", "ssh -i \"" + keyFile.toString().replace('\\', '/')
                    + "\" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new");
            environment.put("KUNLUN_SSH_KEY_FILE", keyFile.toString());
        }
        return environment;
    }

    private void cleanupCredentialFiles(Map<String, String> environment) {
        for (String name : List.of("KUNLUN_ASKPASS_FILE", "KUNLUN_SSH_KEY_FILE")) {
            String value = environment.get(name);
            if (value != null) {
                try { Files.deleteIfExists(Path.of(value)); } catch (IOException ignored) {}
            }
        }
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record RepositorySnapshot(String role, Path repositoryRoot, Path contextRoot,
                                     String commit, String dockerfile) {}
    public record ResolvedCommit(String role, String repositoryId, String repositoryUrl,
                                 String ref, String commit) {}
}
