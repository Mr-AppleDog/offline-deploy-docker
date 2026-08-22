package com.example.offlinedemo.controller;

import com.example.offlinedemo.dto.ServiceStatus;
import com.example.offlinedemo.service.HealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    /** 一次性检测全部中间件 */
    @GetMapping("/all")
    public Map<String, Object> all() {
        List<ServiceStatus> results = healthService.checkAll();
        boolean allOk = results.stream().allMatch(ServiceStatus::ok);
        return Map.of("ok", allOk, "results", results);
    }

    @GetMapping("/mysql")
    public ServiceStatus mysql() {
        return healthService.checkMysql();
    }

    @GetMapping("/redis")
    public ServiceStatus redis() {
        return healthService.checkRedis();
    }

    @GetMapping("/rabbitmq")
    public ServiceStatus rabbitmq() {
        return healthService.checkRabbitmq();
    }

    @GetMapping("/minio")
    public ServiceStatus minio() {
        return healthService.checkMinio();
    }
}