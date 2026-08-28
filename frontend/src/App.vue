<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import axios from 'axios'

const api = axios.create({ baseURL: '/api/platform', timeout: 120000 })
const nav = [
  { key: 'dashboard', label: '总览', icon: '⌂' },
  { key: 'projects', label: '项目', icon: '⌘' },
  { key: 'profiles', label: '部署配置', icon: '◇' },
  { key: 'imageFactory', label: '中间件制作', icon: '⬡' },
  { key: 'artifacts', label: '离线制品库', icon: '▣' },
  { key: 'sqls', label: '数据库脚本库', icon: '▤' },
  { key: 'builds', label: '构建与交付', icon: '▷' }
]
const active = ref('dashboard')
const loading = ref(false)
const toast = reactive({ show: false, type: 'ok', text: '' })
const dashboard = ref({ projects: 0, profiles: 0, artifacts: 0, builds: 0, runningBuilds: 0, imageExportTasks: 0, runningImageExports: 0, architecture: 'linux/amd64' })
const system = ref({ git: false, tar: false, docker: false, buildx: false, architecture: 'linux/amd64' })
const targets = ref([])
const catalog = ref([])
const projects = ref([]), profiles = ref([]), artifacts = ref([]), sqlScripts = ref([]), builds = ref([]), imageExportTasks = ref([])
const selectedProjectId = ref(''), selectedBuildId = ref(''), buildLog = ref('')
const selectedImageExportId = ref(''), imageExportLog = ref('')
const showProjectForm = ref(false), showProfileForm = ref(false), showRepositoryForm = ref(false), showApplicationForm = ref(false)
const showImageRegistryForm = ref(false), showRegistryImageForm = ref(false), registryTagsLoading = ref(false)
const showArtifactForm = ref(false), showBuildForm = ref(false), showSqlForm = ref(false)

const projectForm = reactive({ id: '', name: '', appKey: '', description: '', currentVersion: '1.0.0', targetOs: 'kylin-v10', targetArch: 'amd64', backendHealthPath: '/api/health/live', frontendHealthPath: '/' })
const repositoryForm = reactive({ id: '', projectId: '', role: 'BACKEND', url: '', ref: 'main', subdirectory: '.', dockerfile: 'Dockerfile', authType: 'NONE', username: '', secret: '' })
const applicationForm = reactive({ projectId: '', role: 'BACKEND', version: '1.0.0', gitCommit: '', file: null })
const imageRegistryForm = reactive({ id: '', projectId: '', role: 'BACKEND', registryUrl: '', repository: '', authType: 'NONE', username: '', secret: '' })
const registryImageForm = reactive({ projectId: '', role: 'BACKEND', registryId: '', tag: '', version: '', gitCommit: '', targetOs: 'kylin-v10', targetArch: 'amd64' })
const registryTags = ref([])
const profileForm = reactive({
  id: '', name: '', environment: '生产环境', targetOs: 'kylin-v10', targetArch: 'amd64',
  frontendPort: 80, timezone: 'Asia/Shanghai', javaOptions: '-Xms256m -Xmx1024m', middleware: []
})
const artifactForm = reactive({ component: 'docker-engine', version: '', arch: 'amd64', file: null })
const imageExportForm = reactive({ component: 'mysql', version: '8.0', targetOs: 'kylin-v10', targetArch: 'amd64' })
const sqlForm = reactive({ kind: 'INIT', name: '', targetVersion: '', file: null })
const buildForm = reactive({
  projectId: '', profileId: '', packageType: 'BOOTSTRAP', fromVersion: '', targetVersion: '1.0.0',
  packageRevision: 'r1', updateScope: ['BACKEND', 'FRONTEND'], dbMigrationRequired: false,
  dbInitSqlIds: [], dbMigrationSqlIds: [], artifactSelection: {},
  targetOs: 'kylin-v10', targetArch: 'amd64'
})
const selectedProject = computed(() => projects.value.find(p => p.id === selectedProjectId.value))
const selectedBuild = computed(() => builds.value.find(b => b.id === selectedBuildId.value))
const selectedImageExport = computed(() => imageExportTasks.value.find(t => t.id === selectedImageExportId.value))
const middlewareImageExportTasks = computed(() => imageExportTasks.value.filter(t => !t.applicationRole))
const buildProject = computed(() => projects.value.find(p => p.id === buildForm.projectId))
const compatibleProfiles = computed(() => profiles.value.filter(p => !buildProject.value || p.targetArch === buildProject.value.targetArch))
const requiredArtifacts = computed(() => {
  const profile = profiles.value.find(p => p.id === buildForm.profileId)
  const mw = profile?.middleware?.map(m => m.component) || []
  return ['docker-engine', 'docker-compose', 'app-backend', 'app-frontend', ...mw]
})
const INFRA = [{ component: 'docker-engine', displayName: 'Docker Engine' }, { component: 'docker-compose', displayName: 'Docker Compose' }, { component: 'app-backend', displayName: '后端应用镜像' }, { component: 'app-frontend', displayName: '前端应用镜像' }]
const importableComponents = computed(() => [...INFRA.filter(c => !c.component.startsWith('app-')), ...catalog.value])
function componentLabel(key) { return catalog.value.find(c => c.component === key)?.displayName || INFRA.find(i => i.component === key)?.displayName || key }
function catalogEntry(component) { return catalog.value.find(c => c.component === component) }
const statusNames = { QUEUED: '排队中', RUNNING: '构建中', SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消' }

function notify(text, type = 'ok') { Object.assign(toast, { show: true, text, type }); clearTimeout(notify.timer); notify.timer = setTimeout(() => { toast.show = false }, 3500) }
function errorMessage(error) { return error.response?.data?.message || error.message || '操作失败' }
async function loadAll(silent = false) {
  if (!silent) loading.value = true
  try {
    const [d, s, p, c, a, q, b, cat, exports] = await Promise.all([api.get('/dashboard'), api.get('/system'), api.get('/projects'), api.get('/profiles'), api.get('/artifacts'), api.get('/sql-scripts'), api.get('/builds'), api.get('/middleware/catalog'), api.get('/image-export-tasks')])
    dashboard.value = d.data; system.value = s.data; projects.value = p.data; profiles.value = c.data; artifacts.value = a.data; sqlScripts.value = q.data; builds.value = b.data
    catalog.value = cat.data; imageExportTasks.value = exports.data
    targets.value = d.data.targets || s.data.targets || []
    if (selectedProjectId.value && !projects.value.some(x => x.id === selectedProjectId.value)) selectedProjectId.value = ''
    if (selectedBuildId.value) await loadBuildLog()
  } catch (error) { if (!silent) notify(errorMessage(error), 'error') } finally { loading.value = false }
}
function switchNav(key) {
  active.value = key
  if (key === 'builds') refreshBuilds()
  if (key === 'imageFactory') {
    if (selectedImageExport.value?.applicationRole) { selectedImageExportId.value = middlewareImageExportTasks.value[0]?.id || ''; imageExportLog.value = '' }
    refreshImageExports()
  }
}
function openProjectForm(project) {
  Object.assign(projectForm, project ? { id: project.id, name: project.name, appKey: project.appKey, description: project.description, currentVersion: project.currentVersion || '1.0.0', targetOs: project.targetOs || 'kylin-v10', targetArch: project.targetArch || 'amd64', backendHealthPath: project.backendHealthPath || '/api/health/live', frontendHealthPath: project.frontendHealthPath || '/' } : { id: '', name: '', appKey: '', description: '', currentVersion: '1.0.0', targetOs: 'kylin-v10', targetArch: 'amd64', backendHealthPath: '/api/health/live', frontendHealthPath: '/' })
  showProjectForm.value = true
}
async function saveProject() {
  try { const response = projectForm.id ? await api.put(`/projects/${projectForm.id}`, { ...projectForm }) : await api.post('/projects', { ...projectForm }); showProjectForm.value = false; selectedProjectId.value = response.data.id; notify('项目已保存'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') }
}
async function removeProject(project) { if (!confirm(`确定删除项目“${project.name}”吗？`)) return; try { await api.delete(`/projects/${project.id}`); notify('项目已删除'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') } }
function repositoryFor(project, role) { return project?.repositories?.find(r => r.role === role) }
function imageRegistryFor(project, role) { return project?.imageRegistries?.find(r => r.role === role) }
function applicationExportTask(projectId, role) { return imageExportTasks.value.find(t => t.projectId === projectId && t.applicationRole === role && t.status !== 'SUCCEEDED') }
function openRepositoryForm(role) {
  const existing = repositoryFor(selectedProject.value, role)
  Object.assign(repositoryForm, existing ? { ...existing, projectId: selectedProject.value.id, secret: '' } : { id: '', projectId: selectedProject.value.id, role, url: '', ref: 'main', subdirectory: '.', dockerfile: 'Dockerfile', authType: 'NONE', username: '', secret: '' })
  showRepositoryForm.value = true
}
async function saveRepository() {
  try { const path = repositoryForm.id ? `/projects/${repositoryForm.projectId}/repositories/${repositoryForm.id}` : `/projects/${repositoryForm.projectId}/repositories`; repositoryForm.id ? await api.put(path, { ...repositoryForm }) : await api.post(path, { ...repositoryForm }); showRepositoryForm.value = false; notify('Git 仓库已绑定'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') }
}
function openImageRegistryForm(role) {
  const existing = imageRegistryFor(selectedProject.value, role)
  Object.assign(imageRegistryForm, existing
    ? { ...existing, projectId: selectedProject.value.id, secret: '' }
    : { id: '', projectId: selectedProject.value.id, role, registryUrl: '', repository: `${selectedProject.value.appKey}-${role.toLowerCase()}`, authType: 'NONE', username: '', secret: '' })
  showImageRegistryForm.value = true
}
async function saveImageRegistry() {
  try {
    const base = `/projects/${imageRegistryForm.projectId}/image-registries`
    imageRegistryForm.id ? await api.put(`${base}/${imageRegistryForm.id}`, { ...imageRegistryForm }) : await api.post(base, { ...imageRegistryForm })
    showImageRegistryForm.value = false; notify('应用镜像仓库已绑定'); await loadAll(true)
  } catch (error) { notify(errorMessage(error), 'error') }
}
async function removeImageRegistry() {
  if (!imageRegistryForm.id || !confirm('确定解除这个应用镜像仓库绑定吗？')) return
  try { await api.delete(`/projects/${imageRegistryForm.projectId}/image-registries/${imageRegistryForm.id}`); showImageRegistryForm.value = false; notify('镜像仓库绑定已解除'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') }
}
async function openRegistryImageForm(role) {
  const project = selectedProject.value
  const registry = imageRegistryFor(project, role)
  if (!repositoryFor(project, role)) { notify(`请先绑定${role === 'BACKEND' ? '后端' : '前端'} Git 仓库`, 'error'); return }
  if (!registry) { openImageRegistryForm(role); return }
  Object.assign(registryImageForm, { projectId: project.id, role, registryId: registry.id, tag: '', version: project.currentVersion || '', gitCommit: '', targetOs: project.targetOs, targetArch: project.targetArch })
  registryTags.value = []; showRegistryImageForm.value = true; registryTagsLoading.value = true
  try {
    registryTags.value = (await api.get(`/projects/${project.id}/image-registries/${registry.id}/tags`)).data.tags || []
    if (registryTags.value.length) { registryImageForm.tag = registryTags.value[0]; useRegistryTag() }
  } catch (error) { notify(errorMessage(error), 'error') } finally { registryTagsLoading.value = false }
}
function useRegistryTag() {
  const suggested = registryImageForm.tag.replace(/^v(?=\d)/, '')
  if (/^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$/.test(suggested)) registryImageForm.version = suggested
}
async function createApplicationRegistryExport() {
  try {
    const response = await api.post('/image-export-tasks', { ...registryImageForm, applicationRole: registryImageForm.role })
    showRegistryImageForm.value = false; selectedImageExportId.value = response.data.id
    notify(response.data.reused ? '已复用同一应用镜像制品' : '应用镜像正在拉取并导出 TAR')
    await refreshImageExports(); await loadAll(true)
  } catch (error) { notify(errorMessage(error), 'error') }
}
function openApplicationForm(role) {
  if (!repositoryFor(selectedProject.value, role)) { notify(`请先绑定${role === 'BACKEND' ? '后端' : '前端'} Git 仓库`, 'error'); return }
  Object.assign(applicationForm, { projectId: selectedProject.value.id, role, version: selectedProject.value.currentVersion || '1.0.0', gitCommit: '', file: null })
  showApplicationForm.value = true
}
async function importApplicationArtifact() {
  if (!applicationForm.file) { notify('请选择 docker save 生成的 tar', 'error'); return }
  const fd = new FormData(); fd.append('file', applicationForm.file); fd.append('role', applicationForm.role); fd.append('version', applicationForm.version); fd.append('gitCommit', applicationForm.gitCommit)
  try { await api.post(`/projects/${applicationForm.projectId}/application-artifacts/import`, fd, { headers: { 'Content-Type': 'multipart/form-data' } }); showApplicationForm.value = false; notify('应用镜像已绑定 Git 提交并入库'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') }
}
function projectApplicationArtifacts(projectId, role) { return artifacts.value.filter(a => a.projectId === projectId && a.applicationRole === role) }
function openSqlForm() { Object.assign(sqlForm, { kind: 'INIT', name: '', targetVersion: '', file: null }); showSqlForm.value = true }
async function importSqlScript() {
  if (!sqlForm.file) { notify('请选择 .sql 文件', 'error'); return }
  const fd = new FormData(); fd.append('file', sqlForm.file); fd.append('kind', sqlForm.kind); fd.append('name', sqlForm.name); fd.append('targetVersion', sqlForm.targetVersion)
  try { await api.post('/sql-scripts', fd, { headers: { 'Content-Type': 'multipart/form-data' } }); showSqlForm.value = false; notify('脚本已入库并计算 SHA256'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') }
}
async function removeSqlScript(s) { if (!confirm(`删除脚本“${s.name}”？`)) return; try { await api.delete(`/sql-scripts/${s.id}`); notify('脚本已删除'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') } }
const initSqlOptions = computed(() => sqlScripts.value.filter(s => s.kind === 'INIT'))
const migrationSqlOptions = computed(() => sqlScripts.value.filter(s => s.kind === 'MIGRATION' && s.targetVersion === buildForm.targetVersion))

function emptyProfile() { return { id: '', name: '', environment: '生产环境', targetOs: 'kylin-v10', targetArch: 'amd64', frontendPort: 80, timezone: 'Asia/Shanghai', javaOptions: '-Xms256m -Xmx1024m', middleware: [] } }
function middlewareSelected(component) { return profileForm.middleware.some(m => m.component === component) }
function initMiddlewareCredentials(entry) { const credentials = {}; for (const c of entry?.credentials || []) credentials[c.key] = c.defaultValue || ''; return credentials }
function toggleMiddleware(component) {
  if (middlewareSelected(component)) { profileForm.middleware = profileForm.middleware.filter(m => m.component !== component); return }
  const entry = catalogEntry(component)
  if (!entry) return
  profileForm.middleware.push({ component, credentials: initMiddlewareCredentials(entry) })
}
const catalogByCategory = computed(() => {
  const groups = {}
  for (const c of catalog.value) { (groups[c.category] = groups[c.category] || []).push(c) }
  return Object.entries(groups)
})
function openProfileForm(profile) {
  if (profile) {
    Object.assign(profileForm, {
      id: profile.id, name: profile.name, environment: profile.environment,
      targetOs: profile.targetOs || 'kylin-v10', targetArch: profile.targetArch || 'amd64',
      frontendPort: profile.frontendPort, timezone: profile.timezone, javaOptions: profile.javaOptions,
      middleware: (profile.middleware || []).map(m => ({ component: m.component, credentials: { ...m.values } }))
    })
  } else {
    Object.assign(profileForm, emptyProfile())
    profileForm.middleware = ['mysql', 'redis', 'rabbitmq', 'minio'].filter(d => catalogEntry(d)).map(d => ({ component: d, credentials: initMiddlewareCredentials(catalogEntry(d)) }))
  }
  showProfileForm.value = true
}
async function generateSecrets() {
  const secretFields = []
  for (const m of profileForm.middleware) {
    const entry = catalogEntry(m.component)
    for (const c of entry?.credentials || []) if (c.secret) secretFields.push({ component: m.component, key: c.key })
  }
  if (!secretFields.length) { notify('当前没有需要密码的中间件'); return }
  try { const values = await Promise.all(secretFields.map(() => api.post('/profiles/generate-password'))); secretFields.forEach((f, i) => { const m = profileForm.middleware.find(x => x.component === f.component); if (m) m.credentials[f.key] = values[i].data.password }); notify(`已生成 ${secretFields.length} 组独立强密码`) } catch (error) { notify(errorMessage(error), 'error') }
}
async function saveProfile() { try { const payload = { name: profileForm.name, environment: profileForm.environment, targetOs: profileForm.targetOs, targetArch: profileForm.targetArch, frontendPort: profileForm.frontendPort, timezone: profileForm.timezone, javaOptions: profileForm.javaOptions, middleware: profileForm.middleware.map(m => ({ component: m.component, credentials: { ...m.credentials } })) }; profileForm.id ? await api.put(`/profiles/${profileForm.id}`, payload) : await api.post('/profiles', payload); showProfileForm.value = false; notify('部署配置已加密保存'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') } }
function openArtifactForm() { Object.assign(artifactForm, { component: 'docker-engine', version: '', arch: 'amd64', file: null }); showArtifactForm.value = true }
async function importArtifact() {
  if (!artifactForm.file) { notify('请选择 tar/tgz 文件', 'error'); return }
  const fd = new FormData(); fd.append('file', artifactForm.file); fd.append('component', artifactForm.component); fd.append('version', artifactForm.version); fd.append('arch', artifactForm.arch)
  try { await api.post('/artifacts/import', fd, { headers: { 'Content-Type': 'multipart/form-data' } }); showArtifactForm.value = false; notify('制品已导入并计算 SHA256'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') }
}
function openImageFactory(component = 'mysql', version = '', arch = 'amd64') { Object.assign(imageExportForm, { component, version, targetOs: 'kylin-v10', targetArch: arch }); showBuildForm.value = false; active.value = 'imageFactory' }
async function createImageExport() { try { const response = await api.post('/image-export-tasks', { ...imageExportForm }); selectedImageExportId.value = response.data.id; notify(response.data.reused ? '已复用制品库中的同版本 TAR' : '镜像制作任务已进入队列'); await refreshImageExports(); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') } }
async function refreshImageExports() { try { imageExportTasks.value = (await api.get('/image-export-tasks')).data; dashboard.value = (await api.get('/dashboard')).data; artifacts.value = (await api.get('/artifacts')).data; if (selectedImageExportId.value) await loadImageExportLog() } catch { /* 轮询静默失败 */ } }
async function selectImageExport(task) { selectedImageExportId.value = task.id; await loadImageExportLog() }
async function loadImageExportLog() { if (!selectedImageExportId.value) return; try { imageExportLog.value = (await api.get(`/image-export-tasks/${selectedImageExportId.value}/logs`, { responseType: 'text' })).data } catch { imageExportLog.value = '' } }
function artifactOptions(component) { return artifacts.value.filter(x => x.component === component && x.architecture === ('linux/' + buildForm.targetArch) && (!component.startsWith('app-') || (x.projectId === buildForm.projectId && x.version === buildForm.targetVersion))) }
function syncBuildProject() { const p = projects.value.find(x => x.id === buildForm.projectId); if (!p) return; buildForm.targetOs = p.targetOs || 'kylin-v10'; buildForm.targetArch = p.targetArch || 'amd64'; buildForm.fromVersion = p.currentVersion || ''; buildForm.targetVersion = p.currentVersion || '1.0.0'; buildForm.artifactSelection = {}; const compatible = profiles.value.filter(x => x.targetArch === buildForm.targetArch); if (!compatible.some(x => x.id === buildForm.profileId)) buildForm.profileId = compatible[0]?.id || '' }
function openBuildForm() { const p = selectedProject.value || projects.value[0]; const profile = profiles.value.find(x => x.targetArch === (p?.targetArch || 'amd64')); Object.assign(buildForm, { projectId: p?.id || '', profileId: profile?.id || '', packageType: 'BOOTSTRAP', fromVersion: p?.currentVersion || '', targetVersion: p?.currentVersion || '1.0.0', packageRevision: 'r1', updateScope: ['BACKEND', 'FRONTEND'], dbMigrationRequired: false, dbInitSqlIds: [], dbMigrationSqlIds: [], artifactSelection: {}, targetOs: p?.targetOs || 'kylin-v10', targetArch: p?.targetArch || 'amd64' }); showBuildForm.value = true }
async function createBuild() { try { const payload = { ...buildForm, artifactIds: Object.values(buildForm.artifactSelection).filter(Boolean) }; delete payload.artifactSelection; const response = await api.post('/builds', payload); showBuildForm.value = false; selectedBuildId.value = response.data.id; active.value = 'builds'; notify('构建任务已进入队列'); await refreshBuilds() } catch (error) { notify(errorMessage(error), 'error') } }
async function refreshBuilds() { try { builds.value = (await api.get('/builds')).data; dashboard.value = (await api.get('/dashboard')).data; if (selectedBuildId.value) await loadBuildLog() } catch { /* 轮询静默失败 */ } }
async function selectBuild(build) { selectedBuildId.value = build.id; await loadBuildLog() }
async function loadBuildLog() { if (!selectedBuildId.value) return; try { buildLog.value = (await api.get(`/builds/${selectedBuildId.value}/logs`, { responseType: 'text' })).data } catch { buildLog.value = '' } }
async function downloadBuild(build) {
  try {
    const response = await api.get(`/builds/${build.id}/download`, { responseType: 'blob' })
    const disposition = response.headers['content-disposition'] || ''
    const match = disposition.match(/filename\*?=(?:UTF-8'')?"?([^";]+)"?/)
    const fileName = match ? decodeURIComponent(match[1]) : (build.artifactName || '交付物.tar.gz')
    const url = URL.createObjectURL(response.data)
    const a = document.createElement('a')
    a.href = url; a.download = fileName
    document.body.appendChild(a); a.click(); a.remove()
    URL.revokeObjectURL(url)
  } catch (error) { notify(errorMessage(error), 'error') }
}
async function downloadArtifact(artifact) {
  try {
    const response = await api.get(`/artifacts/${artifact.id}/download`, { responseType: 'blob' })
    const url = URL.createObjectURL(response.data); const a = document.createElement('a'); a.href = url; a.download = artifact.fileName || `${artifact.component}-${artifact.version}.tar`; document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url)
  } catch (error) { notify(errorMessage(error), 'error') }
}
function copyChecksum(value) { navigator.clipboard.writeText(value).then(() => notify('SHA256 已复制')).catch(() => notify('复制失败', 'error')) }
function formatDate(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function formatBytes(value) { if (!value) return '0 B'; const units = ['B', 'KB', 'MB', 'GB']; let size = value, unit = 0; while (size >= 1024 && unit < 3) { size /= 1024; unit++ } return `${size.toFixed(unit ? 1 : 0)} ${units[unit]}` }
function short(value, length = 12) { return value ? value.slice(0, length) : '—' }
let poller
watch(() => buildForm.profileId, () => { buildForm.artifactSelection = {} })
onMounted(async () => { await loadAll(); poller = setInterval(() => { if (builds.value.some(b => ['RUNNING', 'QUEUED'].includes(b.status))) refreshBuilds(); if (imageExportTasks.value.some(t => ['RUNNING', 'QUEUED'].includes(t.status))) refreshImageExports() }, 2500) })
onBeforeUnmount(() => clearInterval(poller))
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">交</span><div><strong>离线交付平台</strong><small>部署包构建与制品管理</small></div></div>
      <nav><button v-for="item in nav" :key="item.key" :class="{ active: active === item.key }" @click="switchNav(item.key)"><span>{{ item.icon }}</span>{{ item.label }}<b v-if="item.key === 'builds' && dashboard.runningBuilds">{{ dashboard.runningBuilds }}</b></button></nav>
      <div class="arch"><i></i><span>目标架构</span><strong>{{ targets.length ? targets.map(t => t.arch).join(' / ') : 'amd64' }}</strong></div>
    </aside>
    <main class="main">
      <header class="topbar"><div><p class="eyebrow">KUNLUN PACKAGE STUDIO</p><h1>{{ nav.find(n => n.key === active)?.label }}</h1></div><div class="top-actions"><span class="worker" :class="system.docker ? 'ready' : 'warn'">● {{ system.docker ? '构建机就绪' : 'Docker 未就绪' }}</span><button class="icon-btn" @click="loadAll">↻</button></div></header>

      <section v-if="active === 'dashboard'" class="content">
        <div class="hero"><div><span class="pill">麒麟 V10 · amd64 / arm64</span><h2>从镜像与 SQL 到离线交付包</h2><p>从仓库选择或上传前后端镜像 TAR，配套数据库 SQL 与中间件，一键生成可校验、可审计的初始化包或应用更新包。</p><button class="primary" @click="openBuildForm">新建构建任务　→</button></div><div class="flow-card"><div><b>01</b><span>制品入库</span></div><em>→</em><div><b>02</b><span>中间件勾选</span></div><em>→</em><div><b>03</b><span>离线交付</span></div></div></div>
        <div class="metrics"><article><span>项目</span><strong>{{ dashboard.projects }}</strong><small>已接入项目</small></article><article><span>部署配置</span><strong>{{ dashboard.profiles }}</strong><small>站点凭据配置</small></article><article><span>离线制品</span><strong>{{ dashboard.artifacts }}</strong><small>已校验 tar/介质</small></article><article><span>构建任务</span><strong>{{ dashboard.builds }}</strong><small>{{ dashboard.runningBuilds }} 个执行中</small></article></div>
        <div class="two-column"><article class="panel"><div class="panel-head"><div><h3>构建环境</h3><p>创建任务前的必要能力</p></div></div><div class="check-list"><div v-for="item in [['Git',system.git],['tar/gzip',system.tar],['Docker Engine',system.docker]]" :key="item[0]"><span :class="item[1] ? 'dot-ok' : 'dot-bad'"></span><b>{{ item[0] }}</b><em>{{ item[1] ? '可用' : '不可用' }}</em></div></div></article><article class="panel"><div class="panel-head"><div><h3>最近构建</h3><p>最新交付任务状态</p></div><button class="text-btn" @click="active='builds'">查看全部</button></div><div v-if="!builds.length" class="empty">还没有构建任务</div><div v-for="build in builds.slice(0,4)" :key="build.id" class="recent-row"><div class="package-icon">{{ build.packageType === 'BOOTSTRAP' ? 'B' : 'U' }}</div><div><b>{{ build.projectName }} · {{ build.targetVersion }}</b><small>{{ build.stage }}</small></div><span class="status" :class="build.status.toLowerCase()">{{ statusNames[build.status] }}</span></div></article></div>
      </section>

      <section v-else-if="active === 'projects'" class="content">
        <div class="section-actions"><div><h2>项目</h2><p>先固定 x86 / ARM 架构，再分别绑定前后端 Git 与 Docker 镜像路径。</p></div><button class="primary" @click="openProjectForm()">＋ 新建项目</button></div>
        <div class="workspace-grid"><div class="list-panel"><button v-for="project in projects" :key="project.id" class="project-item" :class="{selected:selectedProjectId===project.id}" @click="selectedProjectId=project.id"><span class="project-logo">{{ project.name.slice(0,1).toUpperCase() }}</span><span><b>{{ project.name }}</b><small>{{ project.appKey }} · {{ project.targetArch }} · {{ project.currentVersion || '未设置版本' }}</small></span><em>›</em></button><div v-if="!projects.length" class="empty">还没有项目</div></div>
          <div v-if="selectedProject" class="detail-panel"><div class="detail-title"><div><span class="project-logo large">{{ selectedProject.name.slice(0,1) }}</span><div><h2>{{ selectedProject.name }}</h2><p>{{ selectedProject.description || '暂无描述' }}</p></div></div><div><button class="ghost" @click="openProjectForm(selectedProject)">编辑</button><button class="danger-link" @click="removeProject(selectedProject)">删除</button></div></div>
            <dl class="info-list"><dt>应用标识</dt><dd class="mono">{{ selectedProject.appKey }}</dd><dt>目标架构</dt><dd><span class="pill">{{ selectedProject.targetOs }} · {{ selectedProject.targetArch }}</span></dd><dt>当前版本</dt><dd>{{ selectedProject.currentVersion || '未设置' }}</dd><dt>健康路径</dt><dd class="mono">{{ selectedProject.backendHealthPath }} / {{ selectedProject.frontendHealthPath }}</dd></dl>
            <div class="form-section"><b>源码仓库与应用镜像</b><small>可从 Docker Registry 选择镜像，也可上传已有 TAR；两种方式都绑定 Git commit</small></div>
            <div class="profile-grid">
              <article v-for="role in ['BACKEND','FRONTEND']" :key="role" class="profile-card">
                <div class="profile-top"><span>{{ role === 'BACKEND' ? 'B' : 'F' }}</span><div><h3>{{ role === 'BACKEND' ? '后端' : '前端' }}</h3><p v-if="repositoryFor(selectedProject, role)" class="mono">{{ repositoryFor(selectedProject, role).url }}</p><p v-else>尚未绑定 Git 仓库</p></div><button @click="openRepositoryForm(role)">{{ repositoryFor(selectedProject, role) ? '编辑 Git' : '绑定 Git' }}</button></div>
                <div class="image-registry-row" :class="{ emptyRegistry: !imageRegistryFor(selectedProject, role) }"><div><small>Docker 镜像路径</small><b v-if="imageRegistryFor(selectedProject, role)" class="mono">{{ imageRegistryFor(selectedProject, role).registryUrl.replace(/^https?:\/\//,'') }}/{{ imageRegistryFor(selectedProject, role).repository }}</b><b v-else>尚未绑定镜像仓库</b></div><button @click="openImageRegistryForm(role)">{{ imageRegistryFor(selectedProject, role) ? '编辑' : '绑定' }}</button></div>
                <div v-if="applicationExportTask(selectedProject.id, role)" class="app-export-state" :class="applicationExportTask(selectedProject.id, role).status.toLowerCase()"><div><b>{{ statusNames[applicationExportTask(selectedProject.id, role).status] }}</b><small>{{ applicationExportTask(selectedProject.id, role).stage }}</small></div><span>{{ applicationExportTask(selectedProject.id, role).progress }}%</span></div>
                <div class="profile-services"><div v-for="a in projectApplicationArtifacts(selectedProject.id, role).slice(0,4)" :key="a.id"><b>v{{ a.version }} · {{ short(a.gitCommit,10) }}</b><small>{{ a.sourceType === 'REGISTRY_EXPORT' ? short(a.imageReference,34) : formatDate(a.createdAt) }}</small></div><div v-if="!projectApplicationArtifacts(selectedProject.id, role).length"><b>暂无应用镜像</b><small>从仓库选择，或上传 docker save TAR</small></div></div>
                <footer class="app-image-actions"><span>{{ repositoryFor(selectedProject, role)?.ref || '未设置分支' }}</span><div><button class="text-btn" @click="openRegistryImageForm(role)">从仓库选择</button><button class="text-btn" @click="openApplicationForm(role)">上传 TAR</button></div></footer>
              </article>
            </div>
            <div class="modal-actions"><button class="primary" @click="openBuildForm">进入项目构建　→</button></div>
          </div><div v-else class="detail-panel empty big">选择一个项目查看详情</div></div>
      </section>

      <section v-else-if="active === 'profiles'" class="content"><div class="section-actions"><div><h2>部署配置</h2><p>每个站点一份独立配置；密码加密保存、不会从 API 回显。</p></div><button class="primary" @click="openProfileForm()">＋ 新建配置</button></div><div class="profile-grid"><article v-for="profile in profiles" :key="profile.id" class="profile-card"><div class="profile-top"><span>{{ profile.name.slice(0,1) }}</span><div><h3>{{ profile.name }}</h3><p>{{ profile.environment }} · 修订 r{{ profile.revision }}</p></div><button @click="openProfileForm(profile)">编辑</button></div><div class="profile-services"><div v-for="m in profile.middleware" :key="m.component"><b>{{ componentLabel(m.component) }}</b><small>{{ Object.values(m.configured || {}).every(Boolean) ? '凭据已配置' : '待配置' }}</small></div></div><footer><span>目标 {{ profile.targetOs }} · {{ profile.targetArch }}</span><span>{{ profile.middleware?.length || 0 }} 个中间件</span></footer></article><div v-if="!profiles.length" class="empty bordered">还没有部署配置</div></div><div class="notice"><b>凭据轮换边界</b><p>应用更新包只能复用目标站点已有凭据。修改本页密码会产生新的配置修订，不能直接作为普通应用更新使用。</p></div></section>

      <section v-else-if="active === 'imageFactory'" class="content"><div class="section-actions"><div><h2>中间件镜像制作</h2><p>在 Linux 构建机拉取指定架构镜像，docker save 为 TAR 后写入 MinIO/本地制品库；不会清理 Docker 镜像缓存。</p></div></div><form class="panel" @submit.prevent="createImageExport"><div class="form-grid three"><label><span>中间件</span><select v-model="imageExportForm.component"><option v-for="c in catalog" :key="c.component" :value="c.component">{{ c.displayName }} · {{ c.imageRepo }}</option></select></label><label><span>镜像版本</span><input v-model="imageExportForm.version" required placeholder="8.0"></label><label><span>目标架构</span><select v-model="imageExportForm.targetArch"><option value="amd64">x86 / amd64</option><option value="arm64">ARM / arm64</option></select></label></div><div class="modal-actions"><button class="primary">开始制作 TAR</button></div></form><div class="build-layout"><div class="table-wrap build-table"><table><thead><tr><th>镜像</th><th>架构</th><th>状态</th><th>进度</th><th>创建时间</th><th></th></tr></thead><tbody><tr v-for="task in middlewareImageExportTasks" :key="task.id" :class="{selected:selectedImageExportId===task.id}" @click="selectImageExport(task)"><td><b>{{ task.imageReference }}</b><small>{{ task.reused ? '复用已有制品' : task.stage }}</small></td><td>{{ task.targetArch }}</td><td><span class="status" :class="task.status.toLowerCase()">{{ statusNames[task.status] }}</span></td><td><div class="progress"><i :style="{width:task.progress+'%'}"></i></div></td><td>{{ formatDate(task.createdAt) }}</td><td><button v-if="task.artifactId" class="download" @click.stop="downloadArtifact(artifacts.find(a=>a.id===task.artifactId))">下载 TAR</button></td></tr></tbody></table><div v-if="!middlewareImageExportTasks.length" class="empty">还没有镜像制作任务</div></div><aside class="log-panel"><div class="panel-head"><div><h3>制作日志</h3><p v-if="selectedImageExport">{{ selectedImageExport.imageReference }}</p></div><button v-if="selectedImageExport" class="text-btn" @click="loadImageExportLog">刷新</button></div><pre v-if="selectedImageExport">{{ imageExportLog || (selectedImageExport.reused ? '已复用制品库中的现有 TAR。' : '等待日志输出…') }}</pre><div v-else class="empty">选择任务查看日志</div><div v-if="selectedImageExport?.error" class="error-box">{{ selectedImageExport.error }}</div></aside></div></section>

      <section v-else-if="active === 'artifacts'" class="content"><div class="section-actions"><div><h2>离线制品库</h2><p>统一查看上传制品和从仓库制作的中间件 TAR；应用镜像请从项目页面上传。</p></div><button class="primary" @click="openArtifactForm">＋ 导入基础制品</button></div><div class="filter-row"><span class="pill">{{ artifacts.length }} 个制品</span><span class="pill muted">支持 {{ targets.map(t => t.arch).join(' / ') || 'amd64' }}</span></div><div class="table-wrap"><table><thead><tr><th>组件 / 来源</th><th>版本</th><th>架构</th><th>项目 / Git</th><th>文件</th><th>SHA256</th><th></th></tr></thead><tbody><tr v-for="artifact in artifacts" :key="artifact.id"><td><b>{{ componentLabel(artifact.component) }}</b><small>{{ artifact.sourceType === 'REGISTRY_EXPORT' ? '镜像仓库制作' : '页面上传' }}</small></td><td><span class="version">{{ artifact.version }}</span></td><td><span class="pill muted">{{ artifact.architecture ? artifact.architecture.replace('linux/','') : '—' }}</span></td><td><span>{{ artifact.projectName || '全局制品' }}</span><small v-if="artifact.gitCommit" class="mono">{{ short(artifact.gitCommit,12) }}</small></td><td>{{ artifact.fileName }}<small>{{ formatBytes(artifact.size) }}</small></td><td class="mono" :title="artifact.sha256">{{ short(artifact.sha256,16) }}…</td><td><button class="download" @click="downloadArtifact(artifact)">下载</button></td></tr></tbody></table><div v-if="!artifacts.length" class="empty">暂无离线制品</div></div></section>

      <section v-else-if="active === 'sqls'" class="content"><div class="section-actions"><div><h2>数据库脚本库</h2><p>上传初始化 SQL 与迁移 SQL，构建时按版本选择入包（init SQL 当前仅对 MySQL 生效）。</p></div><button class="primary" @click="openSqlForm">＋ 上传脚本</button></div><div class="filter-row"><span class="pill">{{ sqlScripts.length }} 个脚本</span></div><div class="table-wrap"><table><thead><tr><th>类型</th><th>名称</th><th>目标版本</th><th>文件</th><th>大小</th><th>SHA256</th><th>上传时间</th><th></th></tr></thead><tbody><tr v-for="s in sqlScripts" :key="s.id"><td><span class="pill" :class="s.kind==='INIT'?'':'muted'">{{ s.kind==='INIT'?'初始化':'迁移' }}</span></td><td><b>{{ s.name }}</b></td><td><span class="version">{{ s.targetVersion }}</span></td><td>{{ s.fileName }}</td><td>{{ formatBytes(s.size) }}</td><td class="mono" :title="s.sha256">{{ short(s.sha256,16) }}…</td><td>{{ formatDate(s.createdAt) }}</td><td><button class="danger-link" @click="removeSqlScript(s)">删除</button></td></tr></tbody></table><div v-if="!sqlScripts.length" class="empty">暂无数据库脚本</div></div></section>

      <section v-else class="content"><div class="section-actions"><div><h2>构建与交付</h2><p>任务串行执行；失败会保留日志和现场，不执行 Docker prune。</p></div><button class="primary" @click="openBuildForm">＋ 新建构建</button></div><div class="build-layout"><div class="table-wrap build-table"><table><thead><tr><th>项目 / 版本</th><th>类型</th><th>状态</th><th>进度</th><th>创建时间</th><th></th></tr></thead><tbody><tr v-for="build in builds" :key="build.id" :class="{selected:selectedBuildId===build.id}" @click="selectBuild(build)"><td><b>{{ build.projectName }}</b><small>{{ build.fromVersion ? build.fromVersion+' → ' : '' }}{{ build.targetVersion }}</small></td><td>{{ build.packageType === 'BOOTSTRAP' ? '初始化包' : '应用更新包' }}</td><td><span class="status" :class="build.status.toLowerCase()">{{ statusNames[build.status] }}</span></td><td><div class="progress"><i :style="{width:build.progress+'%'}"></i></div><small>{{ build.stage }}</small></td><td>{{ formatDate(build.createdAt) }}</td><td><button v-if="build.status==='SUCCEEDED'" class="download" @click.stop="downloadBuild(build)">下载</button></td></tr></tbody></table><div v-if="!builds.length" class="empty">还没有构建任务</div></div><aside class="log-panel"><div class="panel-head"><div><h3>任务日志</h3><p v-if="selectedBuild">{{ selectedBuild.projectName }} · {{ selectedBuild.targetVersion }}</p></div><button v-if="selectedBuild" class="text-btn" @click="loadBuildLog">刷新</button></div><pre v-if="selectedBuild">{{ buildLog || '等待日志输出…' }}</pre><div v-else class="empty">选择任务查看日志</div><div v-if="selectedBuild?.error" class="error-box">{{ selectedBuild.error }}</div><div v-if="selectedBuild?.sha256" class="checksum"><b>SHA256</b><code>{{ selectedBuild.sha256 }}</code><button class="text-btn" @click="copyChecksum(selectedBuild.sha256)">复制</button></div></aside></div></section>
    </main>

    <div v-if="showProjectForm" class="modal-backdrop" @click.self="showProjectForm=false"><form class="modal" @submit.prevent="saveProject"><div class="modal-head"><div><h2>{{ projectForm.id ? '编辑项目' : '新建项目' }}</h2><p>目标架构创建后不可修改，后续制品和构建都会继承该架构。</p></div><button type="button" @click="showProjectForm=false">×</button></div><div class="form-grid"><label><span>项目名称</span><input v-model="projectForm.name" required></label><label><span>应用标识</span><input v-model="projectForm.appKey" required placeholder="kunlun-app"></label><label><span>目标架构</span><select v-model="projectForm.targetArch" :disabled="!!projectForm.id"><option value="amd64">x86 / amd64</option><option value="arm64">ARM / arm64</option></select></label><label><span>当前版本</span><input v-model="projectForm.currentVersion"></label><label><span>后端健康路径</span><input v-model="projectForm.backendHealthPath" required placeholder="/actuator/health"></label><label><span>前端健康路径</span><input v-model="projectForm.frontendHealthPath" required placeholder="/"></label><label class="full"><span>描述</span><textarea v-model="projectForm.description" rows="3"></textarea></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showProjectForm=false">取消</button><button class="primary">保存</button></div></form></div>

    <div v-if="showRepositoryForm" class="modal-backdrop" @click.self="showRepositoryForm=false"><form class="modal" @submit.prevent="saveRepository"><div class="modal-head"><div><h2>绑定{{ repositoryForm.role === 'BACKEND' ? '后端' : '前端' }} Git 仓库</h2><p>仓库用于建立镜像与源代码提交之间的追踪关系。</p></div><button type="button" @click="showRepositoryForm=false">×</button></div><div class="form-grid"><label class="full"><span>仓库地址</span><input v-model="repositoryForm.url" required placeholder="https://git.example.com/team/app.git"></label><label><span>默认分支 / Ref</span><input v-model="repositoryForm.ref" required placeholder="main"></label><label><span>认证方式</span><select v-model="repositoryForm.authType"><option value="NONE">无需认证</option><option value="HTTPS">HTTPS Token</option><option value="SSH">SSH 私钥</option></select></label><label v-if="repositoryForm.authType!=='NONE'"><span>用户名</span><input v-model="repositoryForm.username"></label><label v-if="repositoryForm.authType!=='NONE'"><span>{{ repositoryForm.authType==='SSH' ? 'SSH 私钥' : 'Token / 密码' }}</span><input v-model="repositoryForm.secret" type="password" :required="!repositoryForm.id"></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showRepositoryForm=false">取消</button><button class="primary">保存绑定</button></div></form></div>

    <div v-if="showImageRegistryForm" class="modal-backdrop" @click.self="showImageRegistryForm=false"><form class="modal" @submit.prevent="saveImageRegistry"><div class="modal-head"><div><h2>绑定{{ imageRegistryForm.role === 'BACKEND' ? '后端' : '前端' }}镜像仓库</h2><p>填写 Registry 服务地址和该应用镜像在仓库中的路径。</p></div><button type="button" @click="showImageRegistryForm=false">×</button></div><div class="form-grid"><label class="full"><span>Registry 服务地址</span><input v-model="imageRegistryForm.registryUrl" required placeholder="https://harbor.example.com"></label><label class="full"><span>镜像路径</span><input v-model="imageRegistryForm.repository" required placeholder="team/app-backend"><small>例如 Harbor 的“项目名/镜像名”，不要填写标签</small></label><label><span>认证方式</span><select v-model="imageRegistryForm.authType"><option value="NONE">公开仓库 / 无需认证</option><option value="BASIC">用户名 + 密码 / Token</option></select></label><label v-if="imageRegistryForm.authType==='BASIC'"><span>用户名</span><input v-model="imageRegistryForm.username" required></label><label v-if="imageRegistryForm.authType==='BASIC'" class="full"><span>密码或访问令牌</span><input v-model="imageRegistryForm.secret" type="password" :required="!imageRegistryForm.id" :placeholder="imageRegistryForm.id ? '留空表示保持原凭证' : ''"></label></div><div class="modal-actions split-actions"><button v-if="imageRegistryForm.id" type="button" class="danger-link" @click="removeImageRegistry">解除绑定</button><span></span><button type="button" class="ghost" @click="showImageRegistryForm=false">取消</button><button class="primary">保存并读取标签</button></div></form></div>

    <div v-if="showRegistryImageForm" class="modal-backdrop" @click.self="showRegistryImageForm=false"><form class="modal" @submit.prevent="createApplicationRegistryExport"><div class="modal-head"><div><h2>选择{{ registryImageForm.role === 'BACKEND' ? '后端' : '前端' }}应用镜像</h2><p>平台将拉取所选标签、校验项目架构并导出为离线 TAR。</p></div><button type="button" @click="showRegistryImageForm=false">×</button></div><div class="credential-toolbar"><span v-if="registryTagsLoading">正在从 Registry 读取标签…</span><span v-else>已读取 {{ registryTags.length }} 个标签；无标签时也可手工填写</span></div><div class="form-grid"><label class="full"><span>镜像标签</span><select v-if="registryTags.length" v-model="registryImageForm.tag" required @change="useRegistryTag"><option v-for="tag in registryTags" :key="tag" :value="tag">{{ tag }}</option></select><input v-else v-model="registryImageForm.tag" required :disabled="registryTagsLoading" placeholder="例如 1.2.3"></label><label><span>应用版本</span><input v-model="registryImageForm.version" required pattern="[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?" placeholder="1.2.3"></label><label><span>Git Commit ID</span><input v-model="registryImageForm.gitCommit" required pattern="[0-9a-fA-F]{7,64}" placeholder="生成该镜像的 commit SHA"></label><label class="full"><span>目标平台</span><input :value="`${registryImageForm.targetOs} · linux/${registryImageForm.targetArch}`" disabled></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showRegistryImageForm=false">取消</button><button class="primary" :disabled="registryTagsLoading">拉取并生成离线 TAR</button></div></form></div>

    <div v-if="showApplicationForm" class="modal-backdrop" @click.self="showApplicationForm=false"><form class="modal" @submit.prevent="importApplicationArtifact"><div class="modal-head"><div><h2>上传{{ applicationForm.role === 'BACKEND' ? '后端' : '前端' }}应用镜像</h2><p>上传外部构建并 docker save 的 TAR，同时绑定生成它的 Git commit。</p></div><button type="button" @click="showApplicationForm=false">×</button></div><div class="form-grid"><label><span>应用版本</span><input v-model="applicationForm.version" required placeholder="1.2.3"></label><label><span>Git Commit ID</span><input v-model="applicationForm.gitCommit" required pattern="[0-9a-fA-F]{7,64}" placeholder="40 位 commit SHA"></label><label class="full"><span>镜像 TAR</span><input type="file" accept=".tar" @change="applicationForm.file=$event.target.files[0]" required></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showApplicationForm=false">取消</button><button class="primary">上传并绑定</button></div></form></div>

    <div v-if="showSqlForm" class="modal-backdrop" @click.self="showSqlForm=false"><form class="modal" @submit.prevent="importSqlScript"><div class="modal-head"><div><h2>上传数据库脚本</h2><p>初始化 SQL 随 bootstrap 包入 database/init；迁移 SQL 入 database/migrations/目标版本。</p></div><button type="button" @click="showSqlForm=false">×</button></div><div class="form-grid"><label><span>类型</span><select v-model="sqlForm.kind"><option value="INIT">初始化 (INIT)</option><option value="MIGRATION">迁移 (MIGRATION)</option></select></label><label><span>名称</span><input v-model="sqlForm.name" required placeholder="schema-init"></label><label><span>目标版本</span><input v-model="sqlForm.targetVersion" required placeholder="1.1.1"></label><label class="full"><span>SQL 文件</span><input type="file" accept=".sql" @change="sqlForm.file=$event.target.files[0]" required></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showSqlForm=false">取消</button><button class="primary">上传并校验</button></div></form></div>

    <div v-if="showProfileForm" class="modal-backdrop" @click.self="showProfileForm=false"><form class="modal profile-modal" @submit.prevent="saveProfile"><div class="modal-head"><div><h2>{{ profileForm.id ? '编辑部署配置' : '新建部署配置' }}</h2><p>密码不回显；编辑时留空表示保持原密文。</p></div><button type="button" @click="showProfileForm=false">×</button></div><div class="credential-toolbar"><span>勾选需要的中间件，每个组件使用独立密码</span><button type="button" class="ghost" @click="generateSecrets">⚄ 一键生成全部强密码</button></div><div class="form-grid three"><label><span>配置名称</span><input v-model="profileForm.name" required></label><label><span>环境</span><input v-model="profileForm.environment"></label><label><span>前端端口</span><input v-model.number="profileForm.frontendPort" type="number"></label><label><span>目标架构</span><select v-model="profileForm.targetArch"><option value="amd64">麒麟 V10 amd64</option><option value="arm64">麒麟 V10 arm64</option></select></label><label><span>时区</span><input v-model="profileForm.timezone"></label><label><span>Java 参数</span><input v-model="profileForm.javaOptions"></label><div class="form-section full"><b>中间件</b><small>按需勾选</small></div></div><div class="mw-picker" v-for="[category, items] in catalogByCategory" :key="category"><small class="mw-cat">{{ category }}</small><label class="chip" v-for="c in items" :key="c.component"><input type="checkbox" :checked="middlewareSelected(c.component)" @change="toggleMiddleware(c.component)"><span>{{ c.displayName }}</span></label></div><div v-for="m in profileForm.middleware" :key="m.component" class="mw-creds"><div class="form-section"><b>{{ componentLabel(m.component) }}</b></div><div class="form-grid three"><label v-for="cred in (catalogEntry(m.component)?.credentials || [])" :key="cred.key"><span>{{ cred.label }}</span><input v-if="cred.secret" v-model="m.credentials[cred.key]" :required="!profileForm.id && cred.required" type="password"><input v-else v-model="m.credentials[cred.key]"></label></div></div><div class="modal-actions"><button type="button" class="ghost" @click="showProfileForm=false">取消</button><button class="primary">加密保存</button></div></form></div>

    <div v-if="showArtifactForm" class="modal-backdrop" @click.self="showArtifactForm=false"><form class="modal" @submit.prevent="importArtifact"><div class="modal-head"><div><h2>导入离线制品</h2><p>上传 tar/tgz，平台计算 SHA256 并入库。</p></div><button type="button" @click="showArtifactForm=false">×</button></div><div class="form-grid"><label><span>组件</span><select v-model="artifactForm.component"><option v-for="c in importableComponents" :key="c.component" :value="c.component">{{ c.displayName }}</option></select></label><label><span>架构</span><select v-model="artifactForm.arch"><option value="amd64">amd64 (x86_64)</option><option value="arm64">arm64 (aarch64)</option></select></label><label><span>版本</span><input v-model="artifactForm.version" required></label><label class="full"><span>制品文件</span><input type="file" @change="artifactForm.file=$event.target.files[0]" required></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showArtifactForm=false">取消</button><button class="primary">上传并校验</button></div></form></div>

    <div v-if="showBuildForm" class="modal-backdrop" @click.self="showBuildForm=false"><form class="modal build-modal" @submit.prevent="createBuild"><div class="modal-head"><div><h2>项目构建</h2><p>架构继承自项目；中间件由部署配置决定，并为每项选择已入库版本。</p></div><button type="button" @click="showBuildForm=false">×</button></div><div class="form-grid three"><label><span>项目</span><select v-model="buildForm.projectId" required @change="syncBuildProject"><option value="" disabled>请选择</option><option v-for="p in projects" :key="p.id" :value="p.id">{{ p.name }} · {{ p.targetArch }}</option></select></label><label><span>同架构部署配置</span><select v-model="buildForm.profileId" required><option value="" disabled>请选择</option><option v-for="p in compatibleProfiles" :key="p.id" :value="p.id">{{ p.name }} r{{ p.revision }}</option></select></label><label><span>包类型</span><select v-model="buildForm.packageType"><option value="BOOTSTRAP">完整初始化包</option><option value="APP_UPDATE">应用更新包</option></select></label><label><span>项目目标架构</span><input :value="`${buildForm.targetOs} · ${buildForm.targetArch}`" disabled></label><label v-if="buildForm.packageType==='APP_UPDATE'"><span>起始版本</span><input v-model="buildForm.fromVersion" required></label><label><span>目标版本</span><input v-model="buildForm.targetVersion" required></label><label v-if="buildForm.packageType==='BOOTSTRAP'"><span>包修订号</span><input v-model="buildForm.packageRevision"></label><div v-if="buildForm.packageType==='APP_UPDATE'" class="full scope"><span>更新范围</span><label><input v-model="buildForm.updateScope" type="checkbox" value="BACKEND"> 后端</label><label><input v-model="buildForm.updateScope" type="checkbox" value="FRONTEND"> 前端</label></div><template v-if="buildForm.packageType==='APP_UPDATE'"><label v-for="role in buildForm.updateScope" :key="role"><span>{{ role==='BACKEND' ? '后端' : '前端' }}应用镜像</span><select v-model="buildForm.artifactSelection['app-'+role.toLowerCase()]" required><option value="" disabled>请选择</option><option v-for="a in artifactOptions('app-'+role.toLowerCase())" :key="a.id" :value="a.id">{{ a.version }} · {{ short(a.gitCommit,10) }}</option></select></label></template><template v-if="buildForm.packageType==='BOOTSTRAP'"><div class="form-section full"><b>离线制品版本</b><small>前后端镜像必须属于当前项目；中间件缺失时可跳到制作页</small></div><label v-for="component in requiredArtifacts" :key="component"><span>{{ componentLabel(component) }}</span><select v-model="buildForm.artifactSelection[component]" required><option value="" disabled>请选择</option><option v-for="a in artifactOptions(component)" :key="a.id" :value="a.id">{{ a.version }}{{ a.gitCommit ? ' · '+short(a.gitCommit,10) : '' }} · {{ formatBytes(a.size) }}</option></select><button v-if="!artifactOptions(component).length && catalogEntry(component)" type="button" class="text-btn" @click="openImageFactory(component, '', buildForm.targetArch)">没有可用版本，去制作 TAR →</button><small v-else-if="!artifactOptions(component).length">当前项目/架构没有可用制品</small></label><label class="full"><span>数据库初始化 SQL（可多选，按文件名顺序执行）</span><select v-model="buildForm.dbInitSqlIds" multiple size="4"><option v-for="s in initSqlOptions" :key="s.id" :value="s.id">{{ s.name }} · {{ s.fileName }} · v{{ s.targetVersion }}</option></select></label></template><div class="full migration-toggle"><label><input v-model="buildForm.dbMigrationRequired" type="checkbox"> 本版本需要数据库迁移</label></div><label v-if="buildForm.dbMigrationRequired" class="full"><span>迁移 SQL（目标版本 {{ buildForm.targetVersion }}，可多选）</span><select v-model="buildForm.dbMigrationSqlIds" multiple size="4"><option v-for="s in migrationSqlOptions" :key="s.id" :value="s.id">{{ s.name }} · {{ s.fileName }}</option></select></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showBuildForm=false">取消</button><button class="primary">提交构建</button></div></form></div>

    <transition name="toast"><div v-if="toast.show" class="toast" :class="toast.type">{{ toast.type === 'error' ? '!' : '✓' }} {{ toast.text }}</div></transition><div v-if="loading" class="loading-bar"></div>
  </div>
</template>
