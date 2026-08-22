# 前后端分离示例：中间件联通性测试

一个最小可跑的 **Spring Boot 3 + Vue 3** 前后端分离项目。前端只有一个页面，用来验证后端能否正确连通四类中间件：

- **MySQL** — JDBC 直连，执行 `SELECT 1` / `SELECT VERSION()`
- **Redis** — 写入/读取一个 key，并回读 TTL
- **RabbitMQ** — 发一条消息到队列再从队列取回（验证发布/消费全链路）
- **MinIO** — 上传 / stat / 删除一个小对象（bucket 不存在会自动创建）

所有凭据默认指向你环境里已经跑起来的 `192.168.149.128`，开箱即可测通。

> **部署**（Docker、离线机、一键上线）请看唯一权威文档 **[部署手册.md](部署手册.md)**。本 README 只讲本地开发运行。

## 目录结构

```
offline-deploy-doker/
├── backend/                 # Spring Boot 3 后端（Java 17，Maven）
│   ├── pom.xml
│   └── src/main/
│       ├── resources/application.yml   # 中间件连接配置
│       └── java/com/example/offlinedemo/
│           ├── OfflineDemoApplication.java
│           ├── config/      # RabbitMQ / MinIO / CORS 配置
│           ├── controller/HealthController.java
│           ├── service/HealthService.java   # 四个中间件的检测逻辑
│           └── dto/ServiceStatus.java
└── frontend/                # Vue 3 + Vite 前端
    ├── package.json
    ├── vite.config.js       # /api 代理到 localhost:8080
    ├── index.html
    └── src/App.vue          # 单页面：四张卡片 + 测试按钮
```

## 前置要求

- JDK 17+（Spring Boot 3 最低要求 17）
- Maven 3.6+
- Node.js 16+（建议 18/20）

## 后端启动

```bash
cd backend
mvn spring-boot:run
```

启动后监听 `http://localhost:8080`，可直接验证接口：

```
curl http://localhost:8080/api/health/all
curl http://localhost:8080/api/health/mysql
curl http://localhost:8080/api/health/redis
curl http://localhost:8080/api/health/rabbitmq
curl http://localhost:8080/api/health/minio
```

## 前端启动

```bash
cd frontend
npm install
npm run dev
```

打开 `http://localhost:5173`，点「测试全部」或逐项点「测试」即可。

## 中间件连接配置

| 组件 | 地址 | 账号 | 密码 | 说明 |
|---|---|---|---|---|
| MySQL | 192.168.149.128:3306 | root | mrlu | 未指定库，仅做连通性验证 |
| Redis | 192.168.149.128:6379 | — | mrlu | |
| RabbitMQ | 192.168.149.128:5672 | admin | mrlu | |
| MinIO | 192.168.149.128:9000 | mrlu | mrlumrlu | bucket `demo-bucket` |

以上都在 `backend/src/main/resources/application.yml` 中，按需修改。

## 注意事项

1. **中文路径 + npm**：本机 `npm` 在包含中文的路径下会失败。当前工作目录 `E:\个人实战\...` 含中文，
   `frontend/` 目录下的 `npm install` 大概率跑不通。**建议把整个 `frontend` 目录拷贝到纯 ASCII 路径再运行**，
   例如 `C:\Users\cxy784853792\offline-demo-frontend`。后端 Maven 一般不受影响，但若遇编码问题同理处理。
2. `.env` 里没有的内容、或改过密码，直接改 `application.yml` 即可。
3. RabbitMQ 用的是非持久化队列 `demo.health.queue`，服务重启即消失，不影响反复测试。