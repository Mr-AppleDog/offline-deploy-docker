# Kunlun 平台 CI/CD 接入说明(运维手册)

> 本平台自身的构建/测试/部署已接入 k3s GitOps 流水线,复刻 DocLoom 的成熟模式,于 2026-08-23 跑通全绿。本文记录访问方式、架构、运维操作与踩坑,供后续维护查阅。

## 一、访问方式

| 项 | 值 |
|---|---|
| 平台访问地址 | `http://192.168.149.128:30088/` |
| 目标架构 | `linux/amd64`(平台只生产 Kylin x86_64 离线包) |

平台无需登录即可直接访问，页面及 `/api/platform/*` 接口不做身份鉴权。后端拥有宿主机级 Docker 权限，必须只在受控内网开放，严禁直接暴露公网。

## 二、流水线总览

`push main`(或手动 workflow_dispatch)触发单 job `build-deploy`,顺序执行:

```
拉代码(SSH,带重试) → 计算 tag(sha-<短commit>)
 → 后端单元测试门槛(docker run mvn test,以 runner 用户运行)
 → 构建后端镜像(docker buildx,maven 多阶段 → jre-alpine + docker CLI)
 → 构建前端镜像(node:20 → nginx:1.28)
 → 推送 localhost:5000(kunlun-backend / kunlun-frontend)
 → kustomize 改 newTag(两条 images 条目)
 → kubectl kustomize 渲染校验(grep 两个镜像 sha)
 → kubectl apply -k 直推 + rollout status(绕开 Argo fetch 不稳)
 → 冒烟测试(NodePort 30088 的 / 与 /api/health/live)
 → 回写 deploy/staging(best-effort,失败不阻断)
```

## 三、部署拓扑(k3s)

- **namespace**:`kunlun`
- **backend**:Deployment `kunlun-backend`,**特权 pod**(`privileged: true` + hostPath 挂 `/var/run/docker.sock`,等价主机级构建权限,仅限受控构建节点),数据落 local-path PVC `kunlun-data`(20Gi,挂 `/data`),`KUNLUN_PROJECT_ROOT=/workspace`(deploy/docs 已烘焙进镜像)。
- **frontend**:Deployment `kunlun-frontend`,nginx 静态托管,`/api/` 反代到 `backend`。
- **Service**:
  - `backend`(名字固定,ClusterIP 8080)——前端 nginx `proxy_pass http://backend:8080` 直接解析该 DNS。
  - `frontend`(NodePort **30088** → 80)。
- **就绪/存活探针**:`GET /api/health/live`(免鉴权)。
- **镜像**:`localhost:5000/kunlun-{backend,frontend}:sha-<commit>`(内容寻址)。

## 四、文件清单

| 路径 | 作用 |
|---|---|
| `.github/workflows/ci-cd.yml` | 流水线定义 |
| `deploy/gitops/base/` | kustomize 基础清单(backend/frontend Deploy、两个 Service、PVC) |
| `deploy/gitops/overlays/staging/kustomization.yaml` | namespace + 镜像改写,newTag 由 CI 覆盖 |
| `deploy/gitops/bootstrap/application-staging.yaml` | Argo Application(仅作 GitOps 记录参考,未启用) |
| `backend/Dockerfile` | 仓库根 context,烘焙 deploy/docs 进 `/workspace` |
| `frontend/Dockerfile` | node → nginx 多阶段 |
| `compose.platform.yml` | 本地/构建机 compose 部署(与 k3s 共用同一 Dockerfile) |

## 五、一次性运维前置(已完成,重建时照做)

1. **runner**:`192.168.149.128` 上为 `Mr-AppleDog/offline-deploy-docker` 注册 after self-hosted runner(名 `mrlu-VMware-Virtual-Platform-kunlun`,systemd 常驻)。
2. **SSH 部署密钥**:节点 `~/.ssh/kunlun_deploy`(ed25519,读写部署密钥挂在仓库上)。原因见「踩坑 1」。

## 六、踩坑记录(均已修复)

1. **github.com HTTPS 在本节点被墙**——git/checkout 报 `Recv failure: 连接被对方重置`,SSH(22/443)畅通。解决:仓库挂 ed25519 读写部署密钥,CI 用 `GIT_SSH_COMMAND` 统一走 SSH。**切勿**用全局 `git config url.insteadOf` 把 https→ssh,会连带污染同属 mrlu 用户的 DocLoom runner 并因权限失败。
2. **节点 40G 根盘偏小**——CI 构建缓存反复把 `/` 推到 95%,触发 k3s `node.kubernetes.io/disk-pressure:NoSchedule` 污点,pod 全 Pending。`docker system prune -af`(勿带 `--volumes`)回收 ~11G;污点等约 5 分钟自动消除,或 `kubectl taint nodes <node> node.kubernetes.io/disk-pressure:NoSchedule-` 手动摘。
3. **mvn test root 属主污染**——测试容器以 root 跑会把 `backend/target/**` 落成 root 属主,下次 checkout clean 权限不够。已改为 `--user $(id -u):$(id -g)`,m2 缓存用 `/home/mrlu/.m2-kunlun`。
4. **apk/npm 国内源**——`dl-cdn.alpinelinux.org` 单请求 8s 超时、`mirrors.aliyun.com` 0.35s:backend 已换 aliyun alpine 源,frontend 已换 npmmirror。
5. **SSH 克隆残留**——上一轮未提交的 kustomize 改动会让 `git clone .` 报目录非空,已改为每次克隆前清空工作目录。
6. **后端慢启动(约 68s)**——liveness 探针已放宽到 `initialDelaySeconds:120` + `failureThreshold:5`,防慢启动误杀。

## 七、日常运维操作

```bash
# 看流水线状态
gh run list --repo Mr-AppleDog/offline-deploy-docker --limit 5
gh run watch <run-id> --repo Mr-AppleDog/offline-deploy-docker --exit-status
# 失败看日志
gh run view <run-id> --repo Mr-AppleDog/offline-deploy-docker --log-failed

# pod / 服务状态
export KUBECONFIG=/home/mrlu/.kube/config
kubectl -n kunlun get pods,svc,pvc
kubectl -n kunlun logs deploy/kunlun-backend --tail=50

# 冒烟
curl -s http://192.168.149.128:30088/api/health/live

# 磁盘告急时(重要,见踩坑 2)
docker system prune -af
```

## 八、后续待办(可选加固)

- **backend 构建慢**:maven 仍用默认中央仓库(无 aliyun 镜像),冷启动约 16 分钟,可仿 DocLoom 加 `maven-settings.xml`。
- **磁盘清理自动化**:在 workflow 里加定期 `docker builder prune`,或加 cron,避免磁盘压力反复出现。
- **docs 烘焙的副作用**:backend Dockerfile 把 `docs/` COPY 进镜像,任何文档改动都会触发后端镜像重建(文档本身在 k3s 下并不被使用)。可考虑只烘焙打包必需的 `deploy/` 与 README,把 docs 移出镜像。
