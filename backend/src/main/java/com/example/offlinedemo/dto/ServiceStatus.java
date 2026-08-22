package com.example.offlinedemo.dto;

/**
 * 单个中间件的检测结果。
 * service: 服务名（mysql / redis / rabbitmq / minio）
 * ok:      是否连通
 * message: 描述信息
 * costMs:  本次检测耗时（毫秒）
 */
public record ServiceStatus(String service, boolean ok, String message, long costMs) {

    public static ServiceStatus ok(String service, String message, long costMs) {
        return new ServiceStatus(service, true, message, costMs);
    }

    public static ServiceStatus fail(String service, String message, long costMs) {
        return new ServiceStatus(service, false, message, costMs);
    }
}