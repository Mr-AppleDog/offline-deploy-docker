package com.example.offlinedemo.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class BuildExecutorConfig {
    @Bean(destroyMethod = "shutdown")
    public ExecutorService buildExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kunlun-build-worker");
            thread.setDaemon(true);
            return thread;
        });
    }
}
