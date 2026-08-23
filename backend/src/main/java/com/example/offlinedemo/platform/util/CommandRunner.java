package com.example.offlinedemo.platform.util;

import com.example.offlinedemo.platform.config.PlatformProperties;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Component
public class CommandRunner {
    private final Duration timeout;

    public CommandRunner(PlatformProperties properties) {
        timeout = Duration.ofMinutes(Math.max(1, properties.getCommandTimeoutMinutes()));
    }

    public Result run(List<String> command, Path workingDirectory, Map<String, String> environment,
                      Consumer<String> output) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) builder.directory(workingDirectory.toFile());
        if (environment != null) builder.environment().putAll(environment);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        List<String> lines = new ArrayList<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader buffered = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = buffered.readLine()) != null) {
                    lines.add(line);
                    if (output != null) output.accept(line);
                }
            } catch (IOException e) {
                if (output != null) output.accept("读取命令输出失败：" + e.getMessage());
            }
        }, "kunlun-command-output");
        reader.setDaemon(true);
        reader.start();
        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            reader.join(5000);
            throw new IOException("命令执行超时：" + display(command));
        }
        reader.join(5000);
        Result result = new Result(process.exitValue(), String.join(System.lineSeparator(), lines));
        if (result.exitCode != 0) {
            throw new CommandFailedException("命令执行失败（" + result.exitCode + "）：" + display(command), result);
        }
        return result;
    }

    public Result run(List<String> command, Path workingDirectory, Consumer<String> output)
            throws IOException, InterruptedException {
        return run(command, workingDirectory, Map.of(), output);
    }

    private String display(List<String> command) {
        return command.stream().map(value -> value.contains(" ") ? '"' + value + '"' : value)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    public record Result(int exitCode, String output) {}

    public static class CommandFailedException extends IOException {
        private final Result result;
        public CommandFailedException(String message, Result result) {
            super(message);
            this.result = result;
        }
        public Result getResult() { return result; }
    }
}
