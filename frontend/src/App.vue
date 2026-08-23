<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import axios from 'axios'

const api = axios.create({ baseURL: '/api/platform', timeout: 120000 })
api.interceptors.request.use(config => {
  const token = localStorage.getItem('kunlun-admin-token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})
api.interceptors.response.use(response => response, async error => {
  if (error.response?.status === 401 && !error.config?._tokenRetried) {
    const token = prompt('请输入 Kunlun 平台管理令牌')
    if (token) {
      localStorage.setItem('kunlun-admin-token', token)
      error.config._tokenRetried = true
      error.config.headers.Authorization = `Bearer ${token}`
      return api.request(error.config)
    }
  }
  return Promise.reject(error)
})
const nav = [
  { key: 'dashboard', label: '总览', icon: '⌂' },
  { key: 'projects', label: '项目与仓库', icon: '⌘' },
  { key: 'profiles', label: '部署配置', icon: '◇' },
  { key: 'artifacts', label: '离线制品库', icon: '▣' },
  { key: 'builds', label: '构建与交付', icon: '▷' }
]
const active = ref('dashboard')
const loading = ref(false)
const toast = reactive({ show: false, type: 'ok', text: '' })
const dashboard = ref({ projects: 0, profiles: 0, artifacts: 0, builds: 0, runningBuilds: 0, architecture: 'linux/amd64' })
const system = ref({ git: false, tar: false, docker: false, buildx: false, architecture: 'linux/amd64' })
const projects = ref([]), profiles = ref([]), artifacts = ref([]), builds = ref([])
const selectedProjectId = ref(''), selectedBuildId = ref(''), buildLog = ref('')
const showProjectForm = ref(false), showRepoForm = ref(false), showProfileForm = ref(false)
const showArtifactForm = ref(false), showBuildForm = ref(false)

const projectForm = reactive({ id: '', name: '', appKey: '', description: '', currentVersion: '1.0.0', backendHealthPath: '/api/health/live', frontendHealthPath: '/' })
const repoForm = reactive({ id: '', role: 'BACKEND', url: '', ref: 'main', subdirectory: '.', dockerfile: 'Dockerfile', authType: 'NONE', username: '', secret: '' })
const profileForm = reactive({
  id: '', name: '', environment: '生产环境', mysqlDatabase: 'kunlun_app', mysqlRootUsername: 'root',
  mysqlRootPassword: '', mysqlUsername: 'kunlun_app', mysqlPassword: '', redisDatabase: 0,
  redisPassword: '', rabbitmqUsername: 'kunlun_app', rabbitmqPassword: '', rabbitmqVhost: '/',
  minioAccessKey: 'kunlunadmin', minioSecretKey: '', minioBucket: 'kunlun-app', frontendPort: 80,
  timezone: 'Asia/Shanghai', javaOptions: '-Xms256m -Xmx1024m'
})
const artifactForm = reactive({ component: 'docker-engine', version: '', sourcePath: '' })
const buildForm = reactive({
  projectId: '', profileId: '', packageType: 'BOOTSTRAP', fromVersion: '', targetVersion: '1.0.0',
  packageRevision: 'r1', updateScope: ['BACKEND', 'FRONTEND'], dbMigrationRequired: false,
  databaseInitDirectory: '', databaseMigrationDirectory: '', artifactSelection: {}
})
const selectedProject = computed(() => projects.value.find(p => p.id === selectedProjectId.value))
const selectedBuild = computed(() => builds.value.find(b => b.id === selectedBuildId.value))
const requiredArtifacts = ['docker-engine', 'docker-compose', 'mysql', 'redis', 'rabbitmq', 'minio']
const componentNames = { 'docker-engine': 'Docker Engine', 'docker-compose': 'Docker Compose', mysql: 'MySQL', redis: 'Redis', rabbitmq: 'RabbitMQ', minio: 'MinIO', postgresql: 'PostgreSQL', kafka: 'Kafka', elasticsearch: 'Elasticsearch' }
const statusNames = { QUEUED: '排队中', RUNNING: '构建中', SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消' }

function notify(text, type = 'ok') { Object.assign(toast, { show: true, text, type }); clearTimeout(notify.timer); notify.timer = setTimeout(() => { toast.show = false }, 3500) }
function errorMessage(error) { return error.response?.data?.message || error.message || '操作失败' }
async function loadAll(silent = false) {
  if (!silent) loading.value = true
  try {
    const [d, s, p, c, a, b] = await Promise.all([api.get('/dashboard'), api.get('/system'), api.get('/projects'), api.get('/profiles'), api.get('/artifacts'), api.get('/builds')])
    dashboard.value = d.data; system.value = s.data; projects.value = p.data; profiles.value = c.data; artifacts.value = a.data; builds.value = b.data
    if (selectedProjectId.value && !projects.value.some(x => x.id === selectedProjectId.value)) selectedProjectId.value = ''
    if (selectedBuildId.value) await loadBuildLog()
  } catch (error) { if (!silent) notify(errorMessage(error), 'error') } finally { loading.value = false }
}
function switchNav(key) { active.value = key; if (key === 'builds') refreshBuilds() }
function openProjectForm(project) {
  Object.assign(projectForm, project ? { id: project.id, name: project.name, appKey: project.appKey, description: project.description, currentVersion: project.currentVersion || '1.0.0', backendHealthPath: project.backendHealthPath || '/api/health/live', frontendHealthPath: project.frontendHealthPath || '/' } : { id: '', name: '', appKey: '', description: '', currentVersion: '1.0.0', backendHealthPath: '/api/health/live', frontendHealthPath: '/' })
  showProjectForm.value = true
}
async function saveProject() {
  try { const response = projectForm.id ? await api.put(`/projects/${projectForm.id}`, { ...projectForm }) : await api.post('/projects', { ...projectForm }); showProjectForm.value = false; selectedProjectId.value = response.data.id; notify('项目已保存'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') }
}
async function removeProject(project) { if (!confirm(`确定删除项目“${project.name}”吗？`)) return; try { await api.delete(`/projects/${project.id}`); notify('项目已删除'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') } }
function openRepoForm(role = 'BACKEND', repository = null) {
  Object.assign(repoForm, repository ? { id: repository.id, role: repository.role, url: repository.url, ref: repository.ref, subdirectory: repository.subdirectory, dockerfile: repository.dockerfile, authType: repository.authType, username: repository.username || '', secret: '' } : { id: '', role, url: '', ref: 'main', subdirectory: '.', dockerfile: 'Dockerfile', authType: 'NONE', username: '', secret: '' }); showRepoForm.value = true
}
async function saveRepository() { try { const base = `/projects/${selectedProjectId.value}/repositories`; repoForm.id ? await api.put(`${base}/${repoForm.id}`, { ...repoForm }) : await api.post(base, { ...repoForm }); showRepoForm.value = false; notify('仓库已保存，需重新分析'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') } }
async function removeRepository(repo) { if (!confirm(`删除 ${repo.role} 仓库配置？`)) return; try { await api.delete(`/projects/${selectedProjectId.value}/repositories/${repo.id}`); notify('仓库已删除'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') } }
async function analyzeProject() { loading.value = true; try { await api.post(`/projects/${selectedProjectId.value}/analyze`); notify('仓库分析完成'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') } finally { loading.value = false } }
async function saveAnalysis() { const decisions = Object.fromEntries(selectedProject.value.analysis.findings.map(x => [x.component, x.confirmed])); try { await api.put(`/projects/${selectedProjectId.value}/analysis`, { decisions }); notify('分析结果已确认'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') } }

function emptyProfile() { return { id: '', name: '', environment: '生产环境', mysqlDatabase: 'kunlun_app', mysqlRootUsername: 'root', mysqlRootPassword: '', mysqlUsername: 'kunlun_app', mysqlPassword: '', redisDatabase: 0, redisPassword: '', rabbitmqUsername: 'kunlun_app', rabbitmqPassword: '', rabbitmqVhost: '/', minioAccessKey: 'kunlunadmin', minioSecretKey: '', minioBucket: 'kunlun-app', frontendPort: 80, timezone: 'Asia/Shanghai', javaOptions: '-Xms256m -Xmx1024m' } }
function openProfileForm(profile) { Object.assign(profileForm, profile ? { ...emptyProfile(), ...profile, mysqlRootPassword: '', mysqlPassword: '', redisPassword: '', rabbitmqPassword: '', minioSecretKey: '' } : emptyProfile()); showProfileForm.value = true }
async function generateSecrets() { try { const values = await Promise.all(Array.from({ length: 5 }, () => api.post('/profiles/generate-password'))); [profileForm.mysqlRootPassword, profileForm.mysqlPassword, profileForm.redisPassword, profileForm.rabbitmqPassword, profileForm.minioSecretKey] = values.map(x => x.data.password); notify('已生成五组独立强密码') } catch (error) { notify(errorMessage(error), 'error') } }
async function saveProfile() { try { profileForm.id ? await api.put(`/profiles/${profileForm.id}`, { ...profileForm }) : await api.post('/profiles', { ...profileForm }); showProfileForm.value = false; notify('部署配置已加密保存'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') } }
async function importArtifact() { try { await api.post('/artifacts/import', { ...artifactForm }); showArtifactForm.value = false; notify('制品已导入并计算 SHA256'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') } }
function artifactOptions(component) { return artifacts.value.filter(x => x.component === component) }
function openBuildForm() { const p = selectedProject.value || projects.value[0]; Object.assign(buildForm, { projectId: p?.id || '', profileId: profiles.value[0]?.id || '', packageType: 'BOOTSTRAP', fromVersion: p?.currentVersion || '', targetVersion: p?.currentVersion || '1.0.0', packageRevision: 'r1', updateScope: ['BACKEND', 'FRONTEND'], dbMigrationRequired: false, databaseInitDirectory: '', databaseMigrationDirectory: '', artifactSelection: {} }); showBuildForm.value = true }
async function createBuild() { try { const payload = { ...buildForm, artifactIds: buildForm.packageType === 'BOOTSTRAP' ? Object.values(buildForm.artifactSelection).filter(Boolean) : [] }; delete payload.artifactSelection; const response = await api.post('/builds', payload); showBuildForm.value = false; selectedBuildId.value = response.data.id; active.value = 'builds'; notify('构建任务已进入队列'); await refreshBuilds() } catch (error) { notify(errorMessage(error), 'error') } }
async function refreshBuilds() { try { builds.value = (await api.get('/builds')).data; dashboard.value = (await api.get('/dashboard')).data; if (selectedBuildId.value) await loadBuildLog() } catch { /* 轮询静默失败 */ } }
async function selectBuild(build) { selectedBuildId.value = build.id; await loadBuildLog() }
async function loadBuildLog() { if (!selectedBuildId.value) return; try { buildLog.value = (await api.get(`/builds/${selectedBuildId.value}/logs`, { responseType: 'text' })).data } catch { buildLog.value = '' } }
function downloadBuild(build) { location.href = `/api/platform/builds/${build.id}/download` }
function formatDate(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function formatBytes(value) { if (!value) return '0 B'; const units = ['B', 'KB', 'MB', 'GB']; let size = value, unit = 0; while (size >= 1024 && unit < 3) { size /= 1024; unit++ } return `${size.toFixed(unit ? 1 : 0)} ${units[unit]}` }
function short(value, length = 12) { return value ? value.slice(0, length) : '—' }
let poller
onMounted(async () => { await loadAll(); poller = setInterval(() => { if (builds.value.some(b => ['RUNNING', 'QUEUED'].includes(b.status))) refreshBuilds() }, 2500) })
onBeforeUnmount(() => clearInterval(poller))
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">K</span><div><strong>Kunlun</strong><small>离线交付平台</small></div></div>
      <nav><button v-for="item in nav" :key="item.key" :class="{ active: active === item.key }" @click="switchNav(item.key)"><span>{{ item.icon }}</span>{{ item.label }}<b v-if="item.key === 'builds' && dashboard.runningBuilds">{{ dashboard.runningBuilds }}</b></button></nav>
      <div class="arch"><i></i><span>目标架构</span><strong>linux/amd64</strong></div>
    </aside>
    <main class="main">
      <header class="topbar"><div><p class="eyebrow">KUNLUN PACKAGE STUDIO</p><h1>{{ nav.find(n => n.key === active)?.label }}</h1></div><div class="top-actions"><span class="worker" :class="system.docker && system.buildx ? 'ready' : 'warn'">● {{ system.docker && system.buildx ? '构建机就绪' : 'Docker 未就绪' }}</span><button class="icon-btn" @click="loadAll">↻</button></div></header>

      <section v-if="active === 'dashboard'" class="content">
        <div class="hero"><div><span class="pill">x86_64 单机构建</span><h2>从代码仓库到离线交付包</h2><p>锁定源码 Commit，确认中间件依赖，选择可信 tar 制品，一键生成可校验、可审计的初始化包或应用更新包。</p><button class="primary" @click="openBuildForm">新建构建任务　→</button></div><div class="flow-card"><div><b>01</b><span>源码快照</span></div><em>→</em><div><b>02</b><span>依赖确认</span></div><em>→</em><div><b>03</b><span>离线交付</span></div></div></div>
        <div class="metrics"><article><span>项目</span><strong>{{ dashboard.projects }}</strong><small>已接入项目</small></article><article><span>部署配置</span><strong>{{ dashboard.profiles }}</strong><small>站点凭据配置</small></article><article><span>离线制品</span><strong>{{ dashboard.artifacts }}</strong><small>已校验 tar/介质</small></article><article><span>构建任务</span><strong>{{ dashboard.builds }}</strong><small>{{ dashboard.runningBuilds }} 个执行中</small></article></div>
        <div class="two-column"><article class="panel"><div class="panel-head"><div><h3>构建环境</h3><p>创建任务前的必要能力</p></div></div><div class="check-list"><div v-for="item in [['Git',system.git],['tar/gzip',system.tar],['Docker Engine',system.docker],['Docker Buildx',system.buildx]]" :key="item[0]"><span :class="item[1] ? 'dot-ok' : 'dot-bad'"></span><b>{{ item[0] }}</b><em>{{ item[1] ? '可用' : '不可用' }}</em></div></div></article><article class="panel"><div class="panel-head"><div><h3>最近构建</h3><p>最新交付任务状态</p></div><button class="text-btn" @click="active='builds'">查看全部</button></div><div v-if="!builds.length" class="empty">还没有构建任务</div><div v-for="build in builds.slice(0,4)" :key="build.id" class="recent-row"><div class="package-icon">{{ build.packageType === 'BOOTSTRAP' ? 'B' : 'U' }}</div><div><b>{{ build.projectName }} · {{ build.targetVersion }}</b><small>{{ build.stage }}</small></div><span class="status" :class="build.status.toLowerCase()">{{ statusNames[build.status] }}</span></div></article></div>
      </section>

      <section v-else-if="active === 'projects'" class="content">
        <div class="section-actions"><div><h2>项目与代码仓库</h2><p>支持前后端独立仓库，也可将同一仓库配置成两个不同子目录。</p></div><button class="primary" @click="openProjectForm()">＋ 新建项目</button></div>
        <div class="workspace-grid"><div class="list-panel"><button v-for="project in projects" :key="project.id" class="project-item" :class="{selected:selectedProjectId===project.id}" @click="selectedProjectId=project.id"><span class="project-logo">{{ project.name.slice(0,1).toUpperCase() }}</span><span><b>{{ project.name }}</b><small>{{ project.appKey }} · {{ project.currentVersion || '未设置版本' }}</small></span><em>›</em></button><div v-if="!projects.length" class="empty">还没有项目</div></div>
          <div v-if="selectedProject" class="detail-panel"><div class="detail-title"><div><span class="project-logo large">{{ selectedProject.name.slice(0,1) }}</span><div><h2>{{ selectedProject.name }}</h2><p>{{ selectedProject.description || '暂无描述' }}</p></div></div><div><button class="ghost" @click="openProjectForm(selectedProject)">编辑</button><button class="danger-link" @click="removeProject(selectedProject)">删除</button></div></div>
            <div class="subhead"><div><h3>代码仓库</h3><p>每次分析和构建都会锁定实际 Commit。</p></div><div><button v-if="!selectedProject.repositories.some(r=>r.role==='BACKEND')" class="ghost" @click="openRepoForm('BACKEND')">＋ 后端仓库</button><button v-if="!selectedProject.repositories.some(r=>r.role==='FRONTEND')" class="ghost" @click="openRepoForm('FRONTEND')">＋ 前端仓库</button></div></div>
            <div class="repo-grid"><article v-for="repo in selectedProject.repositories" :key="repo.id" class="repo-card"><div><span class="role" :class="repo.role.toLowerCase()">{{ repo.role }}</span><span class="credential">{{ repo.authType }}{{ repo.credentialConfigured ? ' · 已配置凭据' : '' }}</span></div><h4>{{ repo.url }}</h4><dl><dt>引用</dt><dd>{{ repo.ref }}</dd><dt>构建目录</dt><dd>{{ repo.subdirectory }}</dd><dt>Dockerfile</dt><dd>{{ repo.dockerfile }}</dd><dt>Commit</dt><dd class="mono">{{ short(repo.lockedCommit) }}</dd></dl><footer><button @click="openRepoForm(repo.role,repo)">编辑</button><button @click="removeRepository(repo)">删除</button></footer></article><div v-if="!selectedProject.repositories.length" class="empty bordered">请先配置前端和后端代码仓库</div></div>
            <div class="analysis-block"><div class="subhead"><div><h3>依赖分析</h3><p>自动结果只是建议，打包前需要人工确认。</p></div><button class="primary small-btn" :disabled="loading || !selectedProject.repositories.length" @click="analyzeProject">{{ loading ? '分析中…' : '分析仓库' }}</button></div><div v-if="selectedProject.analysis" class="findings"><label v-for="finding in selectedProject.analysis.findings" :key="finding.component"><input v-model="finding.confirmed" type="checkbox"><span><b>{{ finding.label }}</b><small>{{ finding.category }} · 置信度 {{ Math.round(finding.confidence*100) }}%</small><em>{{ finding.evidence.join('、') }}</em></span></label><button class="ghost save-confirm" @click="saveAnalysis">保存确认结果</button></div><div v-else class="empty bordered">尚未分析仓库</div></div>
          </div><div v-else class="detail-panel empty big">选择一个项目查看详情</div></div>
      </section>

      <section v-else-if="active === 'profiles'" class="content"><div class="section-actions"><div><h2>部署配置</h2><p>每个站点一份独立配置；密码加密保存、不会从 API 回显。</p></div><button class="primary" @click="openProfileForm()">＋ 新建配置</button></div><div class="profile-grid"><article v-for="profile in profiles" :key="profile.id" class="profile-card"><div class="profile-top"><span>{{ profile.name.slice(0,1) }}</span><div><h3>{{ profile.name }}</h3><p>{{ profile.environment }} · 修订 r{{ profile.revision }}</p></div><button @click="openProfileForm(profile)">编辑</button></div><div class="profile-services"><div><b>MySQL</b><small>{{ profile.mysqlUsername }}@{{ profile.mysqlDatabase }}</small></div><div><b>Redis</b><small>DB {{ profile.redisDatabase }}</small></div><div><b>RabbitMQ</b><small>{{ profile.rabbitmqUsername }} · {{ profile.rabbitmqVhost }}</small></div><div><b>MinIO</b><small>{{ profile.minioAccessKey }} · {{ profile.minioBucket }}</small></div></div><footer><span>前端端口 {{ profile.frontendPort }}</span><span>全部凭据已配置 🔒</span></footer></article><div v-if="!profiles.length" class="empty bordered">还没有部署配置</div></div><div class="notice"><b>凭据轮换边界</b><p>应用更新包只能复用目标站点已有凭据。修改本页密码会产生新的配置修订，不能直接作为普通应用更新使用。</p></div></section>

      <section v-else-if="active === 'artifacts'" class="content"><div class="section-actions"><div><h2>离线制品库</h2><p>导入后复制到受控目录并计算 SHA256，当前只接收 linux/amd64。</p></div><button class="primary" @click="showArtifactForm=true">＋ 导入制品</button></div><div class="filter-row"><span class="pill">{{ artifacts.length }} 个制品</span><span class="pill muted">架构 linux/amd64</span></div><div class="table-wrap"><table><thead><tr><th>组件</th><th>版本</th><th>文件</th><th>大小</th><th>SHA256</th><th>导入时间</th></tr></thead><tbody><tr v-for="artifact in artifacts" :key="artifact.id"><td><b>{{ componentNames[artifact.component] || artifact.component }}</b></td><td><span class="version">{{ artifact.version }}</span></td><td>{{ artifact.fileName }}</td><td>{{ formatBytes(artifact.size) }}</td><td class="mono" :title="artifact.sha256">{{ short(artifact.sha256,16) }}…</td><td>{{ formatDate(artifact.createdAt) }}</td></tr></tbody></table><div v-if="!artifacts.length" class="empty">暂无离线制品</div></div></section>

      <section v-else class="content"><div class="section-actions"><div><h2>构建与交付</h2><p>任务串行执行；失败会保留日志和现场，不执行 Docker prune。</p></div><button class="primary" @click="openBuildForm">＋ 新建构建</button></div><div class="build-layout"><div class="table-wrap build-table"><table><thead><tr><th>项目 / 版本</th><th>类型</th><th>状态</th><th>进度</th><th>创建时间</th><th></th></tr></thead><tbody><tr v-for="build in builds" :key="build.id" :class="{selected:selectedBuildId===build.id}" @click="selectBuild(build)"><td><b>{{ build.projectName }}</b><small>{{ build.fromVersion ? build.fromVersion+' → ' : '' }}{{ build.targetVersion }}</small></td><td>{{ build.packageType === 'BOOTSTRAP' ? '初始化包' : '应用更新包' }}</td><td><span class="status" :class="build.status.toLowerCase()">{{ statusNames[build.status] }}</span></td><td><div class="progress"><i :style="{width:build.progress+'%'}"></i></div><small>{{ build.stage }}</small></td><td>{{ formatDate(build.createdAt) }}</td><td><button v-if="build.status==='SUCCEEDED'" class="download" @click.stop="downloadBuild(build)">下载</button></td></tr></tbody></table><div v-if="!builds.length" class="empty">还没有构建任务</div></div><aside class="log-panel"><div class="panel-head"><div><h3>任务日志</h3><p v-if="selectedBuild">{{ selectedBuild.projectName }} · {{ selectedBuild.targetVersion }}</p></div><button v-if="selectedBuild" class="text-btn" @click="loadBuildLog">刷新</button></div><pre v-if="selectedBuild">{{ buildLog || '等待日志输出…' }}</pre><div v-else class="empty">选择任务查看日志</div><div v-if="selectedBuild?.error" class="error-box">{{ selectedBuild.error }}</div><div v-if="selectedBuild?.sha256" class="checksum"><b>SHA256</b><code>{{ selectedBuild.sha256 }}</code></div></aside></div></section>
    </main>

    <div v-if="showProjectForm" class="modal-backdrop" @click.self="showProjectForm=false"><form class="modal" @submit.prevent="saveProject"><div class="modal-head"><div><h2>{{ projectForm.id ? '编辑项目' : '新建项目' }}</h2><p>应用标识用于生成镜像名，健康路径写入 Compose 门禁。</p></div><button type="button" @click="showProjectForm=false">×</button></div><div class="form-grid"><label><span>项目名称</span><input v-model="projectForm.name" required></label><label><span>应用标识</span><input v-model="projectForm.appKey" required placeholder="kunlun-app"></label><label><span>当前版本</span><input v-model="projectForm.currentVersion"></label><label><span>后端健康路径</span><input v-model="projectForm.backendHealthPath" required placeholder="/actuator/health"></label><label><span>前端健康路径</span><input v-model="projectForm.frontendHealthPath" required placeholder="/"></label><label class="full"><span>描述</span><textarea v-model="projectForm.description" rows="3"></textarea></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showProjectForm=false">取消</button><button class="primary">保存</button></div></form></div>

    <div v-if="showRepoForm" class="modal-backdrop" @click.self="showRepoForm=false"><form class="modal wide" @submit.prevent="saveRepository"><div class="modal-head"><div><h2>{{ repoForm.role === 'BACKEND' ? '后端' : '前端' }}仓库</h2><p>支持公开仓库、HTTPS Token 和 SSH 私钥。</p></div><button type="button" @click="showRepoForm=false">×</button></div><div class="form-grid"><label><span>角色</span><select v-model="repoForm.role" :disabled="!!repoForm.id"><option>BACKEND</option><option>FRONTEND</option></select></label><label><span>认证方式</span><select v-model="repoForm.authType"><option>NONE</option><option>HTTPS</option><option>SSH</option></select></label><label class="full"><span>仓库地址</span><input v-model="repoForm.url" required></label><label><span>分支 / Tag / Commit</span><input v-model="repoForm.ref" required></label><label><span>仓库子目录</span><input v-model="repoForm.subdirectory"></label><label><span>Dockerfile</span><input v-model="repoForm.dockerfile"></label><label v-if="repoForm.authType==='HTTPS'"><span>用户名</span><input v-model="repoForm.username"></label><label v-if="repoForm.authType!=='NONE'" class="full"><span>{{ repoForm.authType==='SSH' ? 'SSH 私钥' : 'Token / 密码' }}{{ repoForm.id ? '（留空不修改）' : '' }}</span><textarea v-model="repoForm.secret" :required="!repoForm.id" rows="4"></textarea></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showRepoForm=false">取消</button><button class="primary">保存仓库</button></div></form></div>

    <div v-if="showProfileForm" class="modal-backdrop" @click.self="showProfileForm=false"><form class="modal profile-modal" @submit.prevent="saveProfile"><div class="modal-head"><div><h2>{{ profileForm.id ? '编辑部署配置' : '新建部署配置' }}</h2><p>密码不回显；编辑时留空表示保持原密文。</p></div><button type="button" @click="showProfileForm=false">×</button></div><div class="credential-toolbar"><span>四个中间件使用独立密码</span><button type="button" class="ghost" @click="generateSecrets">⚄ 生成五组强密码</button></div><div class="form-grid three"><label><span>配置名称</span><input v-model="profileForm.name" required></label><label><span>环境</span><input v-model="profileForm.environment"></label><label><span>前端端口</span><input v-model.number="profileForm.frontendPort" type="number"></label><div class="form-section full"><b>MySQL</b></div><label><span>数据库名</span><input v-model="profileForm.mysqlDatabase"></label><label><span>root 账号</span><input v-model="profileForm.mysqlRootUsername"></label><label><span>root 密码</span><input v-model="profileForm.mysqlRootPassword" :required="!profileForm.id" type="password"></label><label><span>业务账号</span><input v-model="profileForm.mysqlUsername"></label><label><span>业务密码</span><input v-model="profileForm.mysqlPassword" :required="!profileForm.id" type="password"></label><div class="form-section full"><b>Redis / RabbitMQ</b></div><label><span>Redis DB</span><input v-model.number="profileForm.redisDatabase" type="number"></label><label><span>Redis 密码</span><input v-model="profileForm.redisPassword" :required="!profileForm.id" type="password"></label><label><span>RabbitMQ 账号</span><input v-model="profileForm.rabbitmqUsername"></label><label><span>RabbitMQ 密码</span><input v-model="profileForm.rabbitmqPassword" :required="!profileForm.id" type="password"></label><label><span>Virtual Host</span><input v-model="profileForm.rabbitmqVhost"></label><div class="form-section full"><b>MinIO / 运行参数</b></div><label><span>Access Key</span><input v-model="profileForm.minioAccessKey"></label><label><span>Secret Key</span><input v-model="profileForm.minioSecretKey" :required="!profileForm.id" type="password"></label><label><span>默认 Bucket</span><input v-model="profileForm.minioBucket"></label><label><span>时区</span><input v-model="profileForm.timezone"></label><label><span>Java 参数</span><input v-model="profileForm.javaOptions"></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showProfileForm=false">取消</button><button class="primary">加密保存</button></div></form></div>

    <div v-if="showArtifactForm" class="modal-backdrop" @click.self="showArtifactForm=false"><form class="modal" @submit.prevent="importArtifact"><div class="modal-head"><div><h2>导入离线制品</h2><p>源路径是构建机上的本地文件。</p></div><button type="button" @click="showArtifactForm=false">×</button></div><div class="form-grid"><label><span>组件</span><select v-model="artifactForm.component"><option v-for="key in Object.keys(componentNames)" :key="key" :value="key">{{ componentNames[key] }}</option></select></label><label><span>版本</span><input v-model="artifactForm.version" required></label><label class="full"><span>构建机源文件路径</span><input v-model="artifactForm.sourcePath" required placeholder="D:\offline-media\mysql.tar"></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showArtifactForm=false">取消</button><button class="primary">导入并校验</button></div></form></div>

    <div v-if="showBuildForm" class="modal-backdrop" @click.self="showBuildForm=false"><form class="modal build-modal" @submit.prevent="createBuild"><div class="modal-head"><div><h2>新建构建任务</h2><p>任务创建后配置将成为不可变快照。</p></div><button type="button" @click="showBuildForm=false">×</button></div><div class="form-grid three"><label><span>项目</span><select v-model="buildForm.projectId" required><option value="" disabled>请选择</option><option v-for="p in projects" :key="p.id" :value="p.id">{{ p.name }}</option></select></label><label><span>部署配置</span><select v-model="buildForm.profileId" required><option value="" disabled>请选择</option><option v-for="p in profiles" :key="p.id" :value="p.id">{{ p.name }} r{{ p.revision }}</option></select></label><label><span>包类型</span><select v-model="buildForm.packageType"><option value="BOOTSTRAP">完整初始化包</option><option value="APP_UPDATE">应用更新包</option></select></label><label v-if="buildForm.packageType==='APP_UPDATE'"><span>起始版本</span><input v-model="buildForm.fromVersion" required></label><label><span>目标版本</span><input v-model="buildForm.targetVersion" required></label><label v-if="buildForm.packageType==='BOOTSTRAP'"><span>包修订号</span><input v-model="buildForm.packageRevision"></label><div v-if="buildForm.packageType==='APP_UPDATE'" class="full scope"><span>更新范围</span><label><input v-model="buildForm.updateScope" type="checkbox" value="BACKEND"> 后端</label><label><input v-model="buildForm.updateScope" type="checkbox" value="FRONTEND"> 前端</label></div><template v-if="buildForm.packageType==='BOOTSTRAP'"><div class="form-section full"><b>x86_64 离线制品版本</b><small>六类制品必须各选一个</small></div><label v-for="component in requiredArtifacts" :key="component"><span>{{ componentNames[component] }}</span><select v-model="buildForm.artifactSelection[component]" required><option value="" disabled>请选择</option><option v-for="a in artifactOptions(component)" :key="a.id" :value="a.id">{{ a.version }} · {{ formatBytes(a.size) }}</option></select></label><label class="full"><span>数据库初始化目录（后端仓库相对路径）</span><input v-model="buildForm.databaseInitDirectory"></label></template><div class="full migration-toggle"><label><input v-model="buildForm.dbMigrationRequired" type="checkbox"> 本版本需要数据库迁移</label></div><label v-if="buildForm.dbMigrationRequired" class="full"><span>迁移目录（后端仓库相对路径）</span><input v-model="buildForm.databaseMigrationDirectory" required></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showBuildForm=false">取消</button><button class="primary">提交构建</button></div></form></div>

    <transition name="toast"><div v-if="toast.show" class="toast" :class="toast.type">{{ toast.type === 'error' ? '!' : '✓' }} {{ toast.text }}</div></transition><div v-if="loading" class="loading-bar"></div>
  </div>
</template>
