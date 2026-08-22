package com.example.offlinedemo.service;

import com.example.offlinedemo.config.RabbitMQConfig;
import com.example.offlinedemo.dto.ServiceStatus;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class HealthService {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;
    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    public HealthService(JdbcTemplate jdbcTemplate,
                         StringRedisTemplate redisTemplate,
                         RabbitTemplate rabbitTemplate,
                         AmqpAdmin amqpAdmin,
                         MinioClient minioClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
        this.minioClient = minioClient;
    }

    public List<ServiceStatus> checkAll() {
        List<ServiceStatus> list = new ArrayList<>();
        list.add(checkMysql());
        list.add(checkRedis());
        list.add(checkRabbitmq());
        list.add(checkMinio());
        return list;
    }

    public ServiceStatus checkMysql() {
        long t0 = System.currentTimeMillis();
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
            long ms = System.currentTimeMillis() - t0;
            return ServiceStatus.ok("mysql", "连接成功，SELECT 1 = " + one + "，版本 " + version, ms);
        } catch (Exception e) {
            return ServiceStatus.fail("mysql", e.getMessage(), System.currentTimeMillis() - t0);
        }
    }

    public ServiceStatus checkRedis() {
        long t0 = System.currentTimeMillis();
        String key = "demo:ping:" + UUID.randomUUID();
        try {
            redisTemplate.opsForValue().set(key, "pong", 30, TimeUnit.SECONDS);
            String value = redisTemplate.opsForValue().get(key);
            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            redisTemplate.delete(key);
            long ms = System.currentTimeMillis() - t0;
            if (!"pong".equals(value)) {
                return ServiceStatus.fail("redis", "读取值与写入值不一致", ms);
            }
            return ServiceStatus.ok("redis", "写入/读取成功（value=" + value + "，TTL=" + ttl + "s）", ms);
        } catch (Exception e) {
            return ServiceStatus.fail("redis", e.getMessage(), System.currentTimeMillis() - t0);
        }
    }

    public ServiceStatus checkRabbitmq() {
        long t0 = System.currentTimeMillis();
        String msg = "ping-" + UUID.randomUUID();
        try {
            // 先确保队列存在：receive 底层是 basicGet，队列必须已存在；显式声明（幂等）兜底
            amqpAdmin.declareQueue(new Queue(RabbitMQConfig.QUEUE, true));
            // 发一条消息到队列，再从同一条队列取回，验证发布/消费整条链路
            rabbitTemplate.convertAndSend(RabbitMQConfig.QUEUE, msg);
            Object received = rabbitTemplate.receiveAndConvert(RabbitMQConfig.QUEUE, 3000);
            long ms = System.currentTimeMillis() - t0;
            if (received == null) {
                return ServiceStatus.fail("rabbitmq", "3 秒内未收到回传消息", ms);
            }
            return ServiceStatus.ok("rabbitmq", "发送/接收成功（收到：" + received + "）", ms);
        } catch (Exception e) {
            return ServiceStatus.fail("rabbitmq", e.getMessage(), System.currentTimeMillis() - t0);
        }
    }

    public ServiceStatus checkMinio() {
        long t0 = System.currentTimeMillis();
        String object = "demo/test-" + UUID.randomUUID() + ".txt";
        byte[] data = "hello minio".getBytes(StandardCharsets.UTF_8);
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            String created = "";
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                created = "（bucket 不存在，已自动创建）";
            }
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(object)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType("text/plain")
                    .build());
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(object).build());
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(object).build());
            long ms = System.currentTimeMillis() - t0;
            return ServiceStatus.ok("minio",
                    "bucket=" + bucket + created + "，上传/读取/删除成功（对象大小 " + stat.size() + " bytes）", ms);
        } catch (Exception e) {
            return ServiceStatus.fail("minio", e.getMessage(), System.currentTimeMillis() - t0);
        }
    }
}