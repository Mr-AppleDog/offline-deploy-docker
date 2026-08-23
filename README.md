# Kunlun 离线 Docker 部署

## Web 离线交付平台

当前项目已经从固定示例打包脚本扩展为可视化离线交付平台。平台运行在可访问代码仓库和 Docker Engine（含 Buildx）的受控构建机上，生成供无外网 Kylin V10 `amd64`/`arm64`（飞腾/鲲鹏）服务器使用的离线包。

主要能力：

- 项目以及前后端 Git 仓库配置，支持公开仓库、HTTPS Token、SSH 私钥和单仓库子目录；
- 锁定实际 Commit，静态分析 Maven、Node、Compose、Dockerfile 和 SQL，识别中间件依赖；
- **中间件注册表**：内置 MySQL、PostgreSQL、人大金仓 KingbaseES、达梦 DM8、瀚高 HighGo、MongoDB、Redis、RabbitMQ、Kafka、RocketMQ、Elasticsearch、MinIO、Nginx、东方通 TongWeb 等 14 类，新增中间件只需加一条目录定义，不写死代码；
- **双架构目标**：Kylin V10 `amd64` 与 `arm64`，产物命名 `-kylin-v10-<arch>`，贯穿 buildx 构建、镜像 tar、Compose 平台与安装脚本校验；
- 管理 Docker、Compose 及各类中间件的离线制品（按架构分区）；
- 按站点配置各中间件独立账号密码，使用 AES-GCM 加密持久化，页面和 API 不回显密文；
- 异步串行构建，输出实时任务阶段、日志、manifest、镜像清单和 SHA256；
- 生成完整初始化包或前端/后端应用更新包；数据库迁移默认随应用更新包交付；
- **平台自身入库真实 MySQL、构建产物入 MinIO**（见下方「平台持久化」），未配置时自动回退本地 JSON 与本地文件。

### 本地开发启动

后端要求 JDK 17、Maven、Git、tar、Docker Engine 和 Buildx。前端要求 Node.js 20+。项目不需要也禁止使用 Anaconda。

```powershell
$env:KUNLUN_ADMIN_TOKEN='请设置足够长的管理令牌'
cd backend
mvn spring-boot:run
```

```powershell
cd frontend
npm ci
npm run dev
```

访问 `http://localhost:5173`。平台数据默认保存在项目根目录的 `.kunlun-builder`。生产环境建议额外设置 Base64 编码的 32 字节 `KUNLUN_SECRET_KEY`；未设置时平台会在数据目录首次生成 `master.key`，该文件丢失后已有密文将无法恢复。

### 容器启动

```powershell
$env:KUNLUN_ADMIN_TOKEN='请设置足够长的管理令牌'
docker compose -f compose.platform.yml up -d --build
```

访问 `http://localhost:8088`。平台后端为了执行镜像构建会挂载 Docker Socket，这等同于主机级构建权限，只能部署在受控构建机，不能直接暴露到公网。代码仓库或额外离线介质目录需要挂载到后端容器后，再填写容器内路径。

### 平台持久化（真实 MySQL + MinIO）

平台自身元数据（项目/配置/制品/构建记录）默认落在本地 `.kunlun-builder/platform-state.json`，构建产物落本地 `deliveries/`。要「对接真实数据库和 MinIO」，设置以下环境变量后重启平台：

| 变量 | 含义 | 示例 |
|---|---|---|
| `KUNLUN_METADB_URL` | 平台元数据库 JDBC 地址 | `jdbc:mysql://192.168.149.128:3306/kunlun_platform?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai` |
| `KUNLUN_METADB_USER` / `KUNLUN_METADB_PASSWORD` | 元数据库账号密码 | `root` / `mrlu` |
| `KUNLUN_STORAGE_TYPE` | 产物存储后端 | `minio`（缺省 `local`） |
| `KUNLUN_MINIO_ENDPOINT` / `_ACCESS_KEY` / `_SECRET_KEY` / `_BUCKET` | MinIO 连接 | `http://192.168.149.128:9000` / `mrlu` / `mrlumrlu` / `kunlun-platform` |

设了 `KUNLUN_METADB_URL` 后平台自动 `CREATE TABLE IF NOT EXISTS kunlun_record` 并持久化；未设则回退本地 JSON。设了 `KUNLUN_STORAGE_TYPE=minio` 后制品与交付物 tar 上传到 MinIO，下载走对象存储；未设则回退本地文件。`KUNLUN_SECRET_KEY`（或 `.kunlun-builder/master.key`）仍作为凭据加密密钥，**不落库**，务必随平台部署一并保留，丢失后旧密文不可恢复。

### 推荐使用顺序

1. 在“项目与仓库”创建项目，配置 BACKEND 和 FRONTEND 仓库并执行分析。
2. 在“部署配置”创建站点配置，使用页面为五类密码生成独立强密码。
3. 在“离线制品库”导入 Docker Engine、Compose 和所需中间件 tar。
4. 在“构建与交付”选择初始化包或应用更新包并提交任务。
5. 构建成功后下载 `.tar.gz`，同时保存页面显示的 SHA256。

应用更新包不会修改中间件账号密码。修改部署配置密码属于凭据轮换，必须通过独立运维流程同步修改已经运行的中间件。

---

本项目用于在无外网的 Kylin Linux `x86_64` 服务器上部署 Spring Boot、Vue、MySQL、Redis、RabbitMQ 和 MinIO。

从 `1.1.1` 开始采用简化目录：中间件安装一次并长期固定，后续版本只更新 backend、frontend 和必要的数据库迁移，不再使用 `packages/releases/current/state`，也不再使用运行时 `kunlun.env`。

> 状态说明：`1.1.1` 简化结构已经在 Kylin V10 `x86_64` 虚拟机完成核心手工部署。Docker、六镜像、双 Compose、中间件健康和应用启动均通过；首次备份、恢复演练及新增一键初始化脚本的全新机器复验仍待完成。

## 文档入口

- [部署总则](部署手册.md)
- [1.1.1 目录与配置规划](docs/目录规划.md)
- [V1：首次手工部署](docs/V1-手工版.md)
- [1.1.1 手工部署实测记录](docs/1.1.1-手工部署实测记录.md)
- [应用与数据库手工升级](docs/应用升级手工版.md)
- [1.1.0 历史实测记录](docs/V1-实测记录.md)
- [V2：脚本化部署规划](docs/V2-脚本版.md)

## 最终运行模型

```text
/opt/Kunlun
├── docker/          # Docker 离线安装介质和 data-root
├── middleware/      # 固定的四个中间件、镜像 tar 和持久化数据
├── application/     # 当前应用 Compose 和分版本应用镜像 tar
├── database/        # 首次初始化 SQL 和分版本迁移 SQL
├── scripts/         # 启停、检查、备份和恢复脚本
├── backups/
├── logs/
└── tmp/
```

实际运行只有两个 Compose 项目：

- `/opt/Kunlun/middleware/compose.middleware.yml`：MySQL、Redis、RabbitMQ、MinIO。
- `/opt/Kunlun/application/compose.app.yml`：backend、frontend。

两套 Compose 使用外部网络 `kunlun-net`。backend 通过 Docker DNS 访问 `mysql:3306`、`redis:6379`、`rabbitmq:5672` 和 `minio:9000`。

## 配置约定

- 不再使用 `/opt/Kunlun/config/kunlun.env`。
- 镜像、路径、端口、库名、账号和密码直接写入对应 Compose。
- 中间件初始化凭据写在 `compose.middleware.yml`。
- backend 必须取得相同连接凭据，因此 `compose.app.yml` 保存同一套凭据副本。
- 两份 Compose 都是站点专用敏感文件，权限必须为 `root:root 0600`。
- 包含两份 Compose 的离线 tar 同样属于敏感材料，不能进入公共仓库或跨站点复用。

## 版本策略

- `1.1.1` 是新的完整初始化包：Docker、四个中间件、backend、frontend、目录和文档全部包含。
- `1.1.2` 及以后默认是应用更新包：只包含 backend/frontend 镜像、`compose.app.yml`、数据库迁移和校验文件。
- 中间件版本和数据目录默认不随应用版本变化。
- 数据库结构变化属于应用升级，必须在升级前备份并按版本执行迁移。
