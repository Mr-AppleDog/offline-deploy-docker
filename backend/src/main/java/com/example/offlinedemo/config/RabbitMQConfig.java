package com.example.offlinedemo.config;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    /** 演示用队列名 */
    public static final String QUEUE = "demo.health.queue";

    @Bean
    public Queue demoQueue() {
        // 持久化队列：RabbitAdmin 会在启动时声明；持久化可避免 broker 重启后队列丢失
        return new Queue(QUEUE, true);
    }
}