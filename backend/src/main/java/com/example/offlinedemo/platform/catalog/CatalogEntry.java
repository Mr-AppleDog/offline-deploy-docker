package com.example.offlinedemo.platform.catalog;

import java.util.ArrayList;
import java.util.List;

/**
 * 中间件注册表条目。每种中间件用一份声明式数据描述如何渲染 compose 服务、
 * 如何向交付应用注入连接环境变量、以及如何备份。新增中间件 = 在
 * {@code middleware-catalog.json} 增加一条，无需改 Java。
 */
public final class CatalogEntry {
    public String component;
    public String displayName;
    public String category;
    /** 镜像仓库前缀，落地镜像名为 {@code imageRepo + ":" + version}。国产产品请以厂商离线 tar 的镜像名填充。 */
    public String imageRepo;
    /** 支持的 CPU 架构，如 ["amd64","arm64"] */
    public List<String> architectures = new ArrayList<>();
    public List<Credential> credentials = new ArrayList<>();
    public List<Volume> volumes = new ArrayList<>();
    /** 暴露端口的紧凑 YAML（可空），如 "[127.0.0.1:15672:15672]" */
    public String ports;
    public ExtraService extraService = new ExtraService();
    public Healthcheck healthcheck = new Healthcheck();
    public List<EnvEntry> appConnections = new ArrayList<>();
    public String backupStrategy;
    public String backupCommand;
    public String notes;

    public Credential credential(String key) {
        return credentials.stream().filter(c -> key.equals(c.key)).findFirst().orElse(null);
    }

    public static final class Credential {
        public String key;
        public String label;
        public boolean secret;       // 是否加密存储
        public boolean required;     // 新建时是否必填
        public boolean anchor;       // 是否产出跨 compose 共享的 x-kunlun-* 锚点
        public String envVar;        // 容器环境变量名（可为空，例如 Redis database 没有容器 env）
        public String defaultValue;
    }

    public static final class Volume {
        public String dir;           // /opt/Kunlun/middleware/<component>/<dir>
        public String container;     // 容器内挂载路径
        public String flags = ":Z";  // 如 ":Z"、":ro,Z"
    }

    public static final class PortMapping {
        public String publish;
    }

    public static final class ExtraService {
        public String hostname;
        /** 紧凑 YAML 原样输出在 command: 之后（可空），形如 ["sh","-ec","..."] 或 server /data ... */
        public String command;
    }

    public static final class Healthcheck {
        /** 紧凑 YAML 原样输出在 test: 之后，形如 ["CMD-SHELL","..."] */
        public String test;
        public String interval = "10s";
        public String timeout = "5s";
        public int retries = 12;
        public String startPeriod;
    }

    /** 服务端渲染成交付应用 backend 的环境变量注入条目（按 middleware 顺序拼接）。 */
    public static final class EnvEntry {
        public String env;
        public String literal;      // 原样输出（主机名/端口号/端点）
        public String template;     // 带 ${credentialKey} / ${timezone} 占位的模板，经 yaml() 加引号
        public String credential;   // 引用本组件凭证：锚点 -> *alias，否则按 quote 决定是否加引号
        public boolean quote = true;
    }
}