package com.example.offlinedemo.platform.controller;

import com.example.offlinedemo.platform.domain.Models;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
public class SystemController {
    @GetMapping("/api/health/live")
    public Map<String, Object> live() { return Map.of("ok", true, "service", "offline-delivery-studio"); }

    @GetMapping("/api/platform/system")
    public Map<String, Object> system() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("architecture", Models.ARCHITECTURE);
        result.put("targets", Models.supportedTargetViews());
        result.put("host", System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
        result.put("git", available(List.of("git", "--version")));
        result.put("tar", available(List.of("tar", "--version")));
        result.put("docker", available(List.of("docker", "info")));
        result.put("buildx", available(List.of("docker", "buildx", "version")));
        return result;
    }

    private boolean available(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) { process.destroyForcibly(); return false; }
            return process.exitValue() == 0;
        } catch (Exception ignored) { return false; }
    }
}
