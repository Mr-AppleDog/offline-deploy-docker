<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import axios from 'axios'

const api = axios.create({ baseURL: '/api/platform', timeout: 120000 })
const nav = [
  { key: 'dashboard', label: '发布工作台', icon: '⌂', group: 'delivery' },
  { key: 'projects', label: '项目与版本', icon: '⌘', group: 'delivery' },
  { key: 'builds', label: '任务中心', icon: '▷', group: 'delivery' },
  { key: 'profiles', label: '部署配置', icon: '◇', group: 'resources' },
  { key: 'artifacts', label: '离线制品', icon: '▣', group: 'resources' },
  { key: 'imageFactory', label: '镜像制作', icon: '⬡', group: 'resources' },
  { key: 'sqls', label: '数据库脚本', icon: '▤', group: 'resources' }
]
const routeFromHash = () => nav.some(item => item.key === location.hash.slice(1)) ? location.hash.slice(1) : 'dashboard'
const active = ref(routeFromHash())
const loading = ref(false)
const toast = reactive({ show: false, type: 'ok', text: '' })
const dashboard = ref({ projects: 0, profiles: 0, artifacts: 0, builds: 0, runningBuilds: 0, imageExportTasks: 0, runningImageExports: 0, architecture: 'linux/amd64' })
const system = ref({ git: false, tar: false, docker: false, buildx: false, architecture: 'linux/amd64' })
const targets = ref([])
const catalog = ref([])
const projects = ref([]), profiles = ref([]), artifacts = ref([]), sqlScripts = ref([]), builds = ref([]), imageExportTasks = ref([])
const selectedProjectId = ref(''), selectedBuildId = ref(''), buildLog = ref('')
const selectedImageExportId = ref(''), imageExportLog = ref('')
const selectedTaskKey = ref(''), taskTypeFilter = ref('ALL'), taskStatusFilter = ref('ALL')
const projectDetailTab = ref('release'), pollingWarning = ref('')
const showProjectForm = ref(false), showProfileForm = ref(false), showRepositoryForm = ref(false), showApplicationForm = ref(false)
const showRegistryImageForm = ref(false), registryTagsLoading = ref(false), registryBinding = ref(false)
const applicationCommitLoading = ref(false), registryCommitLoading = ref(false)
const applicationLatestCommit = ref(''), registryLatestCommit = ref('')
const applicationCommitSource = ref(''), registryCommitSource = ref('')
const showArtifactForm = ref(false), showBuildForm = ref(false), showSqlForm = ref(false)
const artifactSearch = ref(''), artifactProjectFilter = ref('ALL'), artifactArchFilter = ref('ALL')
const loadWarnings = ref([])

const projectForm = reactive({ id: '', name: '', appKey: '', description: '', currentVersion: '1.0.0', targetOs: 'kylin-v10', targetArch: 'amd64', backendHealthPath: '/api/health/live', frontendHealthPath: '/' })
const repositoryForm = reactive({ id: '', projectId: '', role: 'BACKEND', url: '', ref: 'main', subdirectory: '.', dockerfile: 'Dockerfile', authType: 'NONE', username: '', secret: '' })
const applicationForm = reactive({ projectId: '', role: 'BACKEND', version: '1.0.0', gitCommit: '', file: null })
const registryImageForm = reactive({ projectId: '', role: 'BACKEND', registryId: '', tag: '', version: '', gitCommit: '', targetOs: 'kylin-v10', targetArch: 'amd64' })
const registryImages = ref([]), unavailableRegistryTags = ref([]), registryCatalogRepository = ref('')
const profileForm = reactive({
  id: '', projectId: '', projectLocked: false, name: '', environment: '生产环境', deployedVersion: '1.0.0', targetOs: 'kylin-v10', targetArch: 'amd64',
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
const selectedRegistryImage = computed(() => registryImages.value.find(image => image.tag === registryImageForm.tag))
const buildProject = computed(() => projects.value.find(p => p.id === buildForm.projectId))
function profileProjectId(profile) { return profile?.projectId || (projects.value.length === 1 ? projects.value[0].id : '') }
function profileDeployedVersion(profile) { return profile?.deployedVersion || projects.value.find(project => project.id === profileProjectId(profile))?.currentVersion || '' }
const compatibleProfiles = computed(() => profiles.value.filter(p => !buildProject.value || (profileProjectId(p) === buildProject.value.id && p.targetArch === buildProject.value.targetArch)))
const requiredArtifacts = computed(() => {
  const profile = profiles.value.find(p => p.id === buildForm.profileId)
  const mw = profile?.middleware?.map(m => m.component) || []
  return ['docker-engine', 'docker-compose', 'app-backend', 'app-frontend', ...mw]
})
const deliveryNav = computed(() => nav.filter(item => item.group === 'delivery'))
const resourceNav = computed(() => nav.filter(item => item.group === 'resources'))
const unifiedTasks = computed(() => [
  ...builds.value.map(task => ({ key: `BUILD:${task.id}`, kind: 'BUILD', task, id: task.id, projectId: task.projectId, projectName: task.projectName, status: task.status, stage: task.stage, createdAt: task.createdAt })),
  ...imageExportTasks.value.map(task => ({ key: `IMAGE:${task.id}`, kind: task.applicationRole ? 'APP_IMAGE' : 'MIDDLEWARE_IMAGE', task, id: task.id, projectId: task.projectId, projectName: task.projectName, status: task.status, stage: task.stage, createdAt: task.createdAt }))
].sort((left, right) => new Date(right.createdAt || 0) - new Date(left.createdAt || 0)))
const filteredTasks = computed(() => unifiedTasks.value.filter(item =>
  (taskTypeFilter.value === 'ALL' || item.kind === taskTypeFilter.value)
  && (taskStatusFilter.value === 'ALL' || item.status === taskStatusFilter.value)))
const selectedUnifiedTask = computed(() => unifiedTasks.value.find(item => item.key === selectedTaskKey.value))
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
    const requests = [
      ['概览', '/dashboard', value => { dashboard.value = value }],
      ['构建环境', '/system', value => { system.value = value }],
      ['项目', '/projects', value => { projects.value = value }],
      ['部署配置', '/profiles', value => { profiles.value = value }],
      ['制品', '/artifacts', value => { artifacts.value = value }],
      ['数据库脚本', '/sql-scripts', value => { sqlScripts.value = value }],
      ['构建任务', '/builds', value => { builds.value = value }],
      ['中间件目录', '/middleware/catalog', value => { catalog.value = value }],
      ['镜像任务', '/image-export-tasks', value => { imageExportTasks.value = value }]
    ]
    const results = await Promise.allSettled(requests.map(([, path]) => api.get(path)))
    loadWarnings.value = []
    results.forEach((result, index) => {
      if (result.status === 'fulfilled') requests[index][2](result.value.data)
      else loadWarnings.value.push(`${requests[index][0]}加载失败`)
    })
    targets.value = dashboard.value.targets || system.value.targets || []
    if (selectedProjectId.value && !projects.value.some(x => x.id === selectedProjectId.value)) selectedProjectId.value = ''
    if (!selectedProjectId.value && projects.value.length) selectedProjectId.value = projects.value[0].id
    if (selectedBuildId.value) await loadBuildLog()
    if (selectedTaskKey.value && !unifiedTasks.value.some(item => item.key === selectedTaskKey.value)) selectedTaskKey.value = ''
    if (!selectedTaskKey.value && unifiedTasks.value.length) await selectUnifiedTask(unifiedTasks.value[0])
    if (!loadWarnings.value.length) pollingWarning.value = ''
    if (!silent && loadWarnings.value.length) notify(`${loadWarnings.value.join('、')}，其余数据已显示`, 'error')
  } finally { loading.value = false }
}
function switchNav(key) {
  active.value = key
  if (location.hash !== `#${key}`) history.replaceState(null, '', `#${key}`)
  if (key === 'builds') { refreshBuilds(); refreshImageExports() }
  if (key === 'imageFactory') refreshImageExports()
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
function commitHint(value) {
  const text = String(value || '')
  const tagged = text.match(/(?:^|[-_.])sha[-_.]?([0-9a-f]{7,64})(?=$|[-_.])/i)
  const plain = text.match(/^([0-9a-f]{7,64})$/i)
  return (tagged?.[1] || plain?.[1] || '').toLowerCase()
}
async function fetchLatestCommit(projectId, role) {
  return (await api.get(`/projects/${projectId}/repositories/${role}/latest-commit`)).data
}
async function refreshApplicationCommit(force = false) {
  applicationCommitLoading.value = true
  try {
    const resolved = await fetchLatestCommit(applicationForm.projectId, applicationForm.role)
    applicationLatestCommit.value = resolved.commit
    const fromFile = commitHint(applicationForm.file?.name)
    if (force || !fromFile) {
      applicationForm.gitCommit = resolved.commit
      applicationCommitSource.value = `${resolved.ref} 最新提交`
    }
  } catch (error) {
    applicationCommitSource.value = '自动获取失败'
    notify(errorMessage(error), 'error')
  } finally { applicationCommitLoading.value = false }
}
async function refreshRegistryCommit(force = false) {
  const fromImage = selectedRegistryImage.value?.gitCommit || commitHint(registryImageForm.tag)
  if (!force && fromImage) {
    registryImageForm.gitCommit = fromImage
    registryCommitSource.value = selectedRegistryImage.value?.gitSource || '从镜像标签自动识别'
    return
  }
  registryCommitLoading.value = true
  try {
    const resolved = await fetchLatestCommit(registryImageForm.projectId, registryImageForm.role)
    registryLatestCommit.value = resolved.commit
    if (force || !fromImage) {
      registryImageForm.gitCommit = resolved.commit
      registryCommitSource.value = `${resolved.ref} 最新提交`
    }
  } catch (error) {
    registryCommitSource.value = '自动获取失败'
    notify(errorMessage(error), 'error')
  } finally { registryCommitLoading.value = false }
}
async function syncImageRegistries(showNotice = true) {
  const project = selectedProject.value
  if (!project || registryBinding.value) return false
  registryBinding.value = true
  try {
    await api.post(`/projects/${project.id}/image-registries/auto-bind`)
    await loadAll(true)
    if (showNotice) notify('已从 100.113.245.88:5000 自动读取并绑定前后端仓库')
    return true
  } catch (error) {
    notify(errorMessage(error), 'error')
    return false
  } finally { registryBinding.value = false }
}
async function openRegistryImageForm(role) {
  let project = selectedProject.value
  if (!repositoryFor(project, role)) { notify(`请先绑定${role === 'BACKEND' ? '后端' : '前端'} Git 仓库`, 'error'); return }
  if (!await syncImageRegistries(false)) return
  project = selectedProject.value
  const registry = imageRegistryFor(project, role)
  if (!registry) { notify('Registry 中没有找到对应的应用镜像仓库', 'error'); return }
  Object.assign(registryImageForm, { projectId: project.id, role, registryId: registry.id, tag: '', version: project.currentVersion || '', gitCommit: '', targetOs: project.targetOs, targetArch: project.targetArch })
  registryLatestCommit.value = ''; registryCommitSource.value = ''
  registryImages.value = []; unavailableRegistryTags.value = []; registryCatalogRepository.value = ''
  showRegistryImageForm.value = true; registryTagsLoading.value = true
  try {
    const data = (await api.get(`/projects/${project.id}/image-registries/${registry.id}/tags`)).data
    registryImages.value = data.images || []
    unavailableRegistryTags.value = data.unavailableTags || []
    registryCatalogRepository.value = data.repository || ''
    if (registryImages.value.length) { registryImageForm.tag = registryImages.value[0].tag; useRegistryTag() }
  } catch (error) { notify(errorMessage(error), 'error') } finally { registryTagsLoading.value = false }
}
function useRegistryTag() {
  const image = selectedRegistryImage.value
  const suggested = (image?.version || registryImageForm.tag).replace(/^v(?=\d)/, '')
  if (/^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$/.test(suggested)) registryImageForm.version = suggested
  const fromImage = image?.gitCommit || commitHint(registryImageForm.tag)
  registryImageForm.gitCommit = fromImage || registryLatestCommit.value
  registryCommitSource.value = fromImage ? (image?.gitSource || '从镜像标签自动识别') : (registryLatestCommit.value ? 'Git Ref 最新提交' : '')
  if (!registryImageForm.gitCommit && !registryCommitLoading.value) refreshRegistryCommit()
}
function registryImageLabel(image) {
  const parts = [image.tag]
  if (image.createdAt) parts.push(formatDate(image.createdAt))
  if (image.gitCommit) parts.push(`Git ${short(image.gitCommit, 10)}`)
  return parts.join(' · ')
}
async function createApplicationRegistryExport() {
  try {
    const response = await api.post('/image-export-tasks', { ...registryImageForm, applicationRole: registryImageForm.role })
    showRegistryImageForm.value = false; selectedImageExportId.value = response.data.id; selectedTaskKey.value = `IMAGE:${response.data.id}`
    notify(response.data.reused ? '已复用同一应用镜像制品' : '应用镜像正在拉取并导出 TAR')
    await refreshImageExports(); await loadAll(true); switchNav('builds')
  } catch (error) { notify(errorMessage(error), 'error') }
}
async function openApplicationForm(role) {
  if (!repositoryFor(selectedProject.value, role)) { notify(`请先绑定${role === 'BACKEND' ? '后端' : '前端'} Git 仓库`, 'error'); return }
  Object.assign(applicationForm, { projectId: selectedProject.value.id, role, version: selectedProject.value.currentVersion || '1.0.0', gitCommit: '', file: null })
  applicationLatestCommit.value = ''; applicationCommitSource.value = ''
  showApplicationForm.value = true
  await refreshApplicationCommit()
}
function useApplicationFile(file) {
  applicationForm.file = file || null
  const fromFile = commitHint(file?.name)
  applicationForm.gitCommit = fromFile || applicationLatestCommit.value
  applicationCommitSource.value = fromFile ? '从镜像文件名自动识别' : (applicationLatestCommit.value ? 'Git Ref 最新提交' : '')
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

function emptyProfile(projectId = '') {
  const project = projects.value.find(item => item.id === projectId) || selectedProject.value || projects.value[0]
  return { id: '', projectId: project?.id || '', projectLocked: false, name: '', environment: '生产环境', deployedVersion: project?.currentVersion || '1.0.0', targetOs: project?.targetOs || 'kylin-v10', targetArch: project?.targetArch || 'amd64', frontendPort: 80, timezone: 'Asia/Shanghai', javaOptions: '-Xms256m -Xmx1024m', middleware: [] }
}
function syncProfileProject() {
  const project = projects.value.find(item => item.id === profileForm.projectId)
  if (!project) return
  profileForm.targetOs = project.targetOs || 'kylin-v10'
  profileForm.targetArch = project.targetArch || 'amd64'
  if (!profileForm.id) profileForm.deployedVersion = project.currentVersion || '1.0.0'
}
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
function openProfileForm(profile, projectId = '') {
  if (!profile && !projects.value.length) { notify('请先创建项目', 'error'); switchNav('projects'); openProjectForm(); return }
  if (profile) {
    Object.assign(profileForm, {
      id: profile.id, projectId: profileProjectId(profile), projectLocked: Boolean(profile.projectId), name: profile.name, environment: profile.environment,
      deployedVersion: profileDeployedVersion(profile),
      targetOs: profile.targetOs || 'kylin-v10', targetArch: profile.targetArch || 'amd64',
      frontendPort: profile.frontendPort, timezone: profile.timezone, javaOptions: profile.javaOptions,
      middleware: (profile.middleware || []).map(m => ({ component: m.component, credentials: { ...m.values } }))
    })
  } else {
    Object.assign(profileForm, emptyProfile(projectId))
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
async function saveProfile() { try { const payload = { projectId: profileForm.projectId, name: profileForm.name, environment: profileForm.environment, deployedVersion: profileForm.deployedVersion, targetOs: profileForm.targetOs, targetArch: profileForm.targetArch, frontendPort: profileForm.frontendPort, timezone: profileForm.timezone, javaOptions: profileForm.javaOptions, middleware: profileForm.middleware.map(m => ({ component: m.component, credentials: { ...m.credentials } })) }; profileForm.id ? await api.put(`/profiles/${profileForm.id}`, payload) : await api.post('/profiles', payload); showProfileForm.value = false; notify('部署站点已保存'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') } }
function openArtifactForm() { Object.assign(artifactForm, { component: 'docker-engine', version: '', arch: 'amd64', file: null }); showArtifactForm.value = true }
async function importArtifact() {
  if (!artifactForm.file) { notify('请选择 tar/tgz 文件', 'error'); return }
  const fd = new FormData(); fd.append('file', artifactForm.file); fd.append('component', artifactForm.component); fd.append('version', artifactForm.version); fd.append('arch', artifactForm.arch)
  try { await api.post('/artifacts/import', fd, { headers: { 'Content-Type': 'multipart/form-data' } }); showArtifactForm.value = false; notify('制品已导入并计算 SHA256'); await loadAll(true) } catch (error) { notify(errorMessage(error), 'error') }
}
function openImageFactory(component = 'mysql', version = '', arch = 'amd64') { Object.assign(imageExportForm, { component, version, targetOs: 'kylin-v10', targetArch: arch }); showBuildForm.value = false; switchNav('imageFactory') }
async function createImageExport() { try { const response = await api.post('/image-export-tasks', { ...imageExportForm }); selectedImageExportId.value = response.data.id; selectedTaskKey.value = `IMAGE:${response.data.id}`; notify(response.data.reused ? '已复用制品库中的同版本 TAR' : '镜像制作任务已进入队列'); await refreshImageExports(); await loadAll(true); switchNav('builds') } catch (error) { notify(errorMessage(error), 'error') } }
let lastPollingNotice = 0
function reportPollingError(area, error) {
  pollingWarning.value = `${area}刷新失败：${errorMessage(error)}`
  if (Date.now() - lastPollingNotice > 15000) { notify(pollingWarning.value, 'error'); lastPollingNotice = Date.now() }
}
async function refreshImageExports() { try { imageExportTasks.value = (await api.get('/image-export-tasks')).data; dashboard.value = (await api.get('/dashboard')).data; artifacts.value = (await api.get('/artifacts')).data; if (selectedImageExportId.value) await loadImageExportLog(); if (pollingWarning.value.startsWith('镜像任务')) pollingWarning.value = '' } catch (error) { reportPollingError('镜像任务', error) } }
async function loadImageExportLog() { if (!selectedImageExportId.value) return; try { imageExportLog.value = (await api.get(`/image-export-tasks/${selectedImageExportId.value}/logs`, { responseType: 'text' })).data } catch { imageExportLog.value = '' } }
function compareVersions(left, right) {
  const parse = value => String(value || '').split(/[.+-]/).slice(0, 3).map(part => Number.parseInt(part, 10) || 0)
  const a = parse(left), b = parse(right)
  for (let index = 0; index < 3; index++) if (a[index] !== b[index]) return a[index] - b[index]
  return String(left || '').localeCompare(String(right || ''))
}
function projectReleaseVersions(projectId) {
  const backend = new Set(artifacts.value.filter(a => a.projectId === projectId && a.component === 'app-backend').map(a => a.version))
  return [...new Set(artifacts.value.filter(a => a.projectId === projectId && a.component === 'app-frontend' && backend.has(a.version)).map(a => a.version))]
    .sort((a, b) => compareVersions(b, a))
}
function suggestedTargetVersion(project) {
  if (!project) return ''
  return projectReleaseVersions(project.id).find(version => compareVersions(version, project.currentVersion) > 0)
    || projectReleaseVersions(project.id)[0]
    || project.currentVersion
    || ''
}
function artifactOptions(component) {
  return artifacts.value
    .filter(x => x.component === component && x.architecture === ('linux/' + buildForm.targetArch) && (!component.startsWith('app-') || (x.projectId === buildForm.projectId && x.version === buildForm.targetVersion)))
    .sort((a, b) => compareVersions(b.version, a.version) || new Date(b.createdAt || 0) - new Date(a.createdAt || 0))
}
function autoSelectArtifacts() {
  const next = { ...buildForm.artifactSelection }
  const components = buildForm.packageType === 'BOOTSTRAP'
    ? requiredArtifacts.value
    : buildForm.updateScope.map(role => `app-${role.toLowerCase()}`)
  for (const component of components) {
    const options = artifactOptions(component)
    if (!options.some(item => item.id === next[component])) next[component] = options[0]?.id || ''
  }
  for (const component of Object.keys(next)) if (!components.includes(component)) delete next[component]
  buildForm.artifactSelection = next
}
function syncBuildProject() {
  const project = projects.value.find(x => x.id === buildForm.projectId)
  if (!project) return
  buildForm.targetOs = project.targetOs || 'kylin-v10'
  buildForm.targetArch = project.targetArch || 'amd64'
  buildForm.targetVersion = suggestedTargetVersion(project)
  const compatible = profiles.value.filter(x => profileProjectId(x) === project.id && x.targetArch === buildForm.targetArch)
  if (!compatible.some(x => x.id === buildForm.profileId)) buildForm.profileId = compatible[0]?.id || ''
  buildForm.fromVersion = profileDeployedVersion(compatible.find(item => item.id === buildForm.profileId))
  autoSelectArtifacts()
}
function syncBuildProfile() {
  const profile = profiles.value.find(item => item.id === buildForm.profileId)
  if (buildForm.packageType === 'APP_UPDATE') buildForm.fromVersion = profileDeployedVersion(profile)
  autoSelectArtifacts()
}
function changePackageType() {
  const project = buildProject.value
  const profile = profiles.value.find(item => item.id === buildForm.profileId)
  buildForm.fromVersion = buildForm.packageType === 'APP_UPDATE' ? profileDeployedVersion(profile) : ''
  buildForm.targetVersion = suggestedTargetVersion(project)
  autoSelectArtifacts()
}
function openBuildForm(sourceBuild = null) {
  const spec = sourceBuild?.spec
  const project = projects.value.find(item => item.id === (spec?.projectId || selectedProject.value?.id)) || projects.value[0]
  const profile = profiles.value.find(item => item.id === spec?.profileId)
    || profiles.value.find(item => profileProjectId(item) === project?.id && item.targetArch === (project?.targetArch || 'amd64'))
  const targetVersion = spec?.targetVersion || suggestedTargetVersion(project) || project?.currentVersion || ''
  const selected = {}
  for (const artifactId of spec?.artifactIds || []) {
    const artifact = artifacts.value.find(item => item.id === artifactId)
    if (artifact) selected[artifact.component] = artifact.id
  }
  Object.assign(buildForm, {
    projectId: project?.id || '', profileId: profile?.id || '', packageType: spec?.packageType || 'BOOTSTRAP',
    fromVersion: spec?.packageType === 'APP_UPDATE' ? (spec.fromVersion || profileDeployedVersion(profile)) : '', targetVersion,
    packageRevision: spec?.packageRevision || 'r1', updateScope: [...(spec?.updateScope || ['BACKEND', 'FRONTEND'])],
    dbMigrationRequired: spec?.dbMigrationRequired || false, dbInitSqlIds: [...(spec?.dbInitSqlIds || [])],
    dbMigrationSqlIds: [...(spec?.dbMigrationSqlIds || [])], artifactSelection: selected,
    targetOs: project?.targetOs || 'kylin-v10', targetArch: project?.targetArch || 'amd64'
  })
  autoSelectArtifacts()
  showBuildForm.value = true
}
const buildReadiness = computed(() => requiredArtifacts.value.map(component => {
  const options = artifactOptions(component)
  const artifact = options.find(item => item.id === buildForm.artifactSelection[component])
  return { component, options, artifact, ready: Boolean(artifact) }
}))
const activeBuildReadiness = computed(() => buildForm.packageType === 'BOOTSTRAP'
  ? buildReadiness.value
  : buildForm.updateScope.map(role => {
      const component = `app-${role.toLowerCase()}`
      const options = artifactOptions(component)
      const artifact = options.find(item => item.id === buildForm.artifactSelection[component])
      return { component, options, artifact, ready: Boolean(artifact) }
    }))
const buildCanSubmit = computed(() => Boolean(
  buildForm.projectId && buildForm.profileId && buildForm.targetVersion
  && (buildForm.packageType !== 'APP_UPDATE' || (buildForm.fromVersion && buildForm.fromVersion !== buildForm.targetVersion && buildForm.updateScope.length))
  && activeBuildReadiness.value.every(item => item.ready)
  && (!buildForm.dbMigrationRequired || buildForm.dbMigrationSqlIds.length)
))
function openMissingArtifact(item) {
  showBuildForm.value = false
  if (catalogEntry(item.component)) { openImageFactory(item.component, '', buildForm.targetArch); return }
  if (item.component.startsWith('app-')) {
    selectedProjectId.value = buildForm.projectId
    switchNav('projects')
    notify(`请先准备${componentLabel(item.component)}`)
    return
  }
  artifactArchFilter.value = buildForm.targetArch
  switchNav('artifacts')
  notify(`请先导入${componentLabel(item.component)}`)
}
async function createBuild() {
  try {
    const payload = { ...buildForm, artifactIds: Object.values(buildForm.artifactSelection).filter(Boolean) }
    delete payload.artifactSelection
    const response = await api.post('/builds', payload)
    showBuildForm.value = false; selectedBuildId.value = response.data.id; selectedTaskKey.value = `BUILD:${response.data.id}`; switchNav('builds')
    notify('构建任务已进入队列'); await refreshBuilds()
  } catch (error) { notify(errorMessage(error), 'error') }
}
async function refreshBuilds() { try { builds.value = (await api.get('/builds')).data; dashboard.value = (await api.get('/dashboard')).data; if (selectedBuildId.value) await loadBuildLog(); if (pollingWarning.value.startsWith('构建任务')) pollingWarning.value = '' } catch (error) { reportPollingError('构建任务', error) } }
async function loadBuildLog() { if (!selectedBuildId.value) return; try { buildLog.value = (await api.get(`/builds/${selectedBuildId.value}/logs`, { responseType: 'text' })).data } catch { buildLog.value = '' } }
async function selectUnifiedTask(item) {
  selectedTaskKey.value = item.key
  if (item.kind === 'BUILD') { selectedBuildId.value = item.id; await loadBuildLog() }
  else { selectedImageExportId.value = item.id; await loadImageExportLog() }
}
async function viewImageExportTask(task) {
  const item = unifiedTasks.value.find(candidate => candidate.key === `IMAGE:${task.id}`)
  if (item) await selectUnifiedTask(item)
  switchNav('builds')
}
function taskKindLabel(kind) { return kind === 'BUILD' ? '交付构建' : kind === 'APP_IMAGE' ? '应用镜像' : '中间件镜像' }
function taskTitle(item) {
  if (item.kind === 'BUILD') return `${item.task.projectName} · ${buildVersionLabel(item.task)}`
  return item.task.applicationRole ? `${item.task.projectName} · ${componentLabel(item.task.component)}` : item.task.imageReference
}
function taskTarget(item) {
  if (item.kind === 'BUILD') return item.task.packageType === 'BOOTSTRAP' ? '完整初始化包' : '应用更新包'
  return `linux/${item.task.targetArch}`
}
const selectedTaskLog = computed(() => {
  const raw = selectedUnifiedTask.value?.kind === 'BUILD' ? buildLog.value : imageExportLog.value
  return String(raw || '').replace(/\[([0-9]{4}-[0-9]{2}-[0-9]{2}T[^\]]+Z)\]/g, (match, value) => `[${formatDate(value)}]`)
})
function retryUnifiedTask(item) {
  if (item.kind === 'BUILD') { openBuildForm(item.task); return }
  if (item.kind === 'MIDDLEWARE_IMAGE') { openImageFactory(item.task.component, item.task.version, item.task.targetArch); return }
  selectedProjectId.value = item.task.projectId
  projectDetailTab.value = 'sources'
  switchNav('projects')
  notify('请重新选择 Registry 镜像后发起任务')
}
function openTaskArtifact(item) {
  const artifact = artifacts.value.find(candidate => candidate.id === item.task.artifactId)
  if (item.kind === 'BUILD') downloadBuild(item.task)
  else if (artifact) downloadArtifact(artifact)
}
function refreshSelectedTask() {
  if (selectedUnifiedTask.value?.kind === 'BUILD') loadBuildLog()
  else if (selectedUnifiedTask.value) loadImageExportLog()
}
function selectedTaskError(item) { return item?.task?.error || '' }
function selectedTaskChecksum(item) {
  if (!item) return ''
  if (item.kind === 'BUILD') return item.task.sha256 || ''
  return artifacts.value.find(artifact => artifact.id === item.task.artifactId)?.sha256 || ''
}
function startNativeDownload(path, fileName) {
  const a = document.createElement('a')
  a.href = path
  a.download = fileName || ''
  document.body.appendChild(a)
  a.click()
  a.remove()
}
async function downloadBuild(build) {
  try {
    const endpoint = `/builds/${encodeURIComponent(build.id)}/download`
    await api.head(endpoint, { timeout: 15000 })
    startNativeDownload(`/api/platform${endpoint}`, build.artifactName || '交付物.tar.gz')
    notify('下载已开始，可在浏览器下载列表查看进度')
  } catch (error) { notify(errorMessage(error), 'error') }
}
async function downloadArtifact(artifact) {
  try {
    const endpoint = `/artifacts/${encodeURIComponent(artifact.id)}/download`
    await api.head(endpoint, { timeout: 15000 })
    startNativeDownload(`/api/platform${endpoint}`, artifact.fileName || `${artifact.component}-${artifact.version}.tar`)
    notify('下载已开始，可在浏览器下载列表查看进度')
  } catch (error) { notify(errorMessage(error), 'error') }
}
function copyChecksum(value) { navigator.clipboard.writeText(value).then(() => notify('SHA256 已复制')).catch(() => notify('复制失败', 'error')) }
function formatDate(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function formatBytes(value) { if (!value) return '0 B'; const units = ['B', 'KB', 'MB', 'GB']; let size = value, unit = 0; while (size >= 1024 && unit < 3) { size /= 1024; unit++ } return `${size.toFixed(unit ? 1 : 0)} ${units[unit]}` }
function short(value, length = 12) { return value ? value.slice(0, length) : '—' }
function buildVersionLabel(build) { return build.packageType === 'APP_UPDATE' ? `${build.fromVersion} → ${build.targetVersion}` : `v${build.targetVersion}` }
function projectReadiness(project) {
  const version = suggestedTargetVersion(project)
  const roles = ['BACKEND', 'FRONTEND']
  const readyRoles = roles.filter(role => artifacts.value.some(a => a.projectId === project.id && a.applicationRole === role && a.version === version)).length
  const profile = profiles.value.find(item => profileProjectId(item) === project.id && item.targetArch === project.targetArch)
  const components = ['docker-engine', 'docker-compose', ...(profile?.middleware || []).map(item => item.component)]
  const readyResources = components.filter(component => artifacts.value.some(a => a.component === component && a.architecture === `linux/${project.targetArch}`)).length
  const profileReady = Boolean(profile)
  return { version, readyRoles, profileReady, readyResources, resources: components.length, ready: readyRoles === roles.length && profileReady && readyResources === components.length }
}
function projectDeliveryChecks(project) {
  if (!project) return []
  const readiness = projectReadiness(project)
  const profile = profiles.value.find(item => profileProjectId(item) === project.id && item.targetArch === project.targetArch)
  const checks = [
    { key: 'version', label: '发布版本', ready: Boolean(readiness.version), detail: readiness.version ? `v${readiness.version}` : '缺少前后端共同版本', action: 'sources' },
    { key: 'backend', label: '后端应用镜像', ready: artifacts.value.some(a => a.projectId === project.id && a.applicationRole === 'BACKEND' && a.version === readiness.version), detail: readiness.version ? `v${readiness.version}` : '待准备', action: 'sources' },
    { key: 'frontend', label: '前端应用镜像', ready: artifacts.value.some(a => a.projectId === project.id && a.applicationRole === 'FRONTEND' && a.version === readiness.version), detail: readiness.version ? `v${readiness.version}` : '待准备', action: 'sources' },
    { key: 'profile', label: '部署站点', ready: Boolean(profile), detail: profile ? `${profile.name} · 已部署 v${profileDeployedVersion(profile)}` : '未绑定当前项目', action: 'profile' }
  ]
  for (const component of ['docker-engine', 'docker-compose', ...(profile?.middleware || []).map(item => item.component)]) {
    const artifact = artifacts.value.find(item => item.component === component && item.architecture === `linux/${project.targetArch}`)
    checks.push({ key: component, label: componentLabel(component), ready: Boolean(artifact), detail: artifact ? `v${artifact.version}` : `缺少 linux/${project.targetArch} 制品`, action: catalogEntry(component) ? 'factory' : 'artifacts', component })
  }
  return checks
}
function handleDeliveryCheck(project, check) {
  selectedProjectId.value = project.id
  if (check.action === 'sources') { projectDetailTab.value = 'sources'; switchNav('projects'); return }
  if (check.action === 'profile') { switchNav('profiles'); openProfileForm(null, project.id); return }
  if (check.action === 'factory') { openImageFactory(check.component, '', project.targetArch); return }
  artifactArchFilter.value = project.targetArch; switchNav('artifacts')
}
function startDelivery(project = selectedProject.value || projects.value[0]) {
  if (!project) { openProjectForm(); return }
  selectedProjectId.value = project.id
  const missing = projectDeliveryChecks(project).find(check => !check.ready)
  if (missing) { handleDeliveryCheck(project, missing); notify(`请先完成：${missing.label}`); return }
  openBuildForm()
}
const deliveryActionLabel = computed(() => !projects.value.length ? '创建第一个项目' : projectReadiness(selectedProject.value || projects.value[0]).ready ? '创建发布' : '继续交付准备')
const filteredArtifacts = computed(() => {
  const query = artifactSearch.value.trim().toLowerCase()
  return artifacts.value.filter(artifact => {
    const projectMatches = artifactProjectFilter.value === 'ALL'
      || (artifactProjectFilter.value === 'GLOBAL' ? !artifact.projectId : artifact.projectId === artifactProjectFilter.value)
    const archMatches = artifactArchFilter.value === 'ALL' || artifact.architecture === `linux/${artifactArchFilter.value}`
    const text = [componentLabel(artifact.component), artifact.version, artifact.projectName, artifact.gitCommit, artifact.fileName, artifact.imageReference].filter(Boolean).join(' ').toLowerCase()
    return projectMatches && archMatches && (!query || text.includes(query))
  })
})
function handleHashChange() { active.value = routeFromHash() }
let poller
watch(() => buildForm.profileId, syncBuildProfile)
watch(() => [buildForm.targetVersion, buildForm.updateScope.join(',')], autoSelectArtifacts)
onMounted(async () => {
  if (!location.hash) history.replaceState(null, '', '#dashboard')
  window.addEventListener('hashchange', handleHashChange)
  await loadAll()
  poller = setInterval(() => { if (builds.value.some(b => ['RUNNING', 'QUEUED'].includes(b.status))) refreshBuilds(); if (imageExportTasks.value.some(t => ['RUNNING', 'QUEUED'].includes(t.status))) refreshImageExports() }, 2500)
})
onBeforeUnmount(() => { clearInterval(poller); window.removeEventListener('hashchange', handleHashChange) })
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">交</span><div><strong>离线交付平台</strong><small>RELEASE CONSOLE</small></div></div>
      <nav>
        <small class="nav-label">交付工作区</small>
        <button v-for="item in deliveryNav" :key="item.key" :class="{ active: active === item.key }" @click="switchNav(item.key)"><span>{{ item.icon }}</span>{{ item.label }}<b v-if="item.key === 'builds' && unifiedTasks.some(task => ['RUNNING','QUEUED'].includes(task.status))">{{ unifiedTasks.filter(task => ['RUNNING','QUEUED'].includes(task.status)).length }}</b></button>
        <small class="nav-label resources">资源维护</small>
        <button v-for="item in resourceNav" :key="item.key" :class="{ active: active === item.key }" @click="switchNav(item.key)"><span>{{ item.icon }}</span>{{ item.label }}</button>
      </nav>
      <div class="arch"><i></i><span>目标架构</span><strong>{{ targets.length ? targets.map(t => t.arch).join(' / ') : 'amd64' }}</strong></div>
    </aside>
    <main class="main">
      <header class="topbar"><div><p class="eyebrow">KUNLUN RELEASE CONSOLE</p><h1>{{ nav.find(n => n.key === active)?.label }}</h1></div><div class="top-actions"><span v-if="loadWarnings.length || pollingWarning" class="worker warn" :title="pollingWarning">{{ pollingWarning || `${loadWarnings.length} 项数据异常` }}</span><span class="worker" :class="system.docker ? 'ready' : 'warn'">● {{ system.docker ? '构建机就绪' : 'Docker 未就绪' }}</span><button class="icon-btn" title="刷新当前数据" aria-label="刷新当前数据" @click="loadAll">↻</button></div></header>

      <section v-if="active === 'dashboard'" class="content">
        <div class="dashboard-heading"><div><h2>今天要交付什么？</h2><p>从项目开始，平台会逐项检查版本、站点和离线制品。</p></div><button class="primary" @click="startDelivery()">{{ deliveryActionLabel }}</button></div>
        <div class="metrics"><article><span>待处理失败</span><strong>{{ unifiedTasks.filter(item => item.status === 'FAILED').length }}</strong><small>可从任务详情修复重试</small></article><article><span>执行中的任务</span><strong>{{ unifiedTasks.filter(item => ['RUNNING','QUEUED'].includes(item.status)).length }}</strong><small>交付与镜像制作队列</small></article><article><span>已接入项目</span><strong>{{ dashboard.projects }}</strong><small>{{ projects.filter(p => projectReadiness(p).ready).length }} 个可直接构建</small></article><article><span>可用制品</span><strong>{{ dashboard.artifacts }}</strong><small>已完成校验并入库</small></article></div>
        <div class="dashboard-grid">
          <article class="panel release-panel"><div class="panel-head"><div><h3>项目发布准备</h3><p>自动检查应用镜像、基础制品和部署站点</p></div><button class="text-btn" @click="switchNav('projects')">管理项目</button></div><div v-if="!projects.length" class="empty">还没有项目</div><button v-for="project in projects.slice(0,6)" :key="project.id" class="release-row" @click="selectedProjectId=project.id; projectDetailTab='release'; switchNav('projects')"><span class="project-logo">{{ project.name.slice(0,1) }}</span><span><b>{{ project.name }}</b><small>基准 v{{ project.currentVersion || '未设置' }} · 发布 v{{ projectReadiness(project).version || '待准备' }}</small></span><em :class="projectReadiness(project).ready ? 'ready' : 'warn'">{{ projectReadiness(project).ready ? '可以构建' : `${projectDeliveryChecks(project).filter(check => check.ready).length}/${projectDeliveryChecks(project).length} 已就绪` }}</em></button></article>
          <article class="panel"><div class="panel-head"><div><h3>最近任务</h3><p>交付构建和镜像制作统一跟踪</p></div><button class="text-btn" @click="switchNav('builds')">查看全部</button></div><div v-if="!unifiedTasks.length" class="empty">还没有任务</div><div v-for="item in unifiedTasks.slice(0,5)" :key="item.key" class="recent-row"><div class="package-icon">{{ item.kind === 'BUILD' ? '交' : '镜' }}</div><button class="recent-main" @click="selectUnifiedTask(item); switchNav('builds')"><b>{{ taskTitle(item) }}</b><small>{{ taskKindLabel(item.kind) }} · {{ item.stage }}</small></button><span class="status" :class="item.status.toLowerCase()">{{ statusNames[item.status] }}</span><button v-if="item.status==='FAILED'" class="text-btn" @click="retryUnifiedTask(item)">修复重试</button></div></article>
        </div>
        <div class="environment-strip"><span>构建环境</span><b v-for="item in [['Git',system.git],['tar/gzip',system.tar],['Docker Engine',system.docker]]" :key="item[0]"><i :class="item[1] ? 'dot-ok' : 'dot-bad'"></i>{{ item[0] }} {{ item[1] ? '可用' : '不可用' }}</b></div>
      </section>

      <section v-else-if="active === 'projects'" class="content">
        <div class="section-actions"><div><h2>项目与版本</h2><p>在项目内准备发布版本、应用镜像和部署目标。</p></div><button class="primary" @click="openProjectForm()">新建项目</button></div>
        <div class="workspace-grid"><div class="list-panel"><button v-for="project in projects" :key="project.id" class="project-item" :class="{selected:selectedProjectId===project.id}" @click="selectedProjectId=project.id; projectDetailTab='release'"><span class="project-logo">{{ project.name.slice(0,1).toUpperCase() }}</span><span><b>{{ project.name }}</b><small>{{ project.appKey }} · {{ project.targetArch }} · 基准 v{{ project.currentVersion || '未设置' }}</small></span><em>›</em></button><div v-if="!projects.length" class="empty">还没有项目</div></div>
          <div v-if="selectedProject" class="detail-panel"><div class="detail-title"><div><span class="project-logo large">{{ selectedProject.name.slice(0,1) }}</span><div><h2>{{ selectedProject.name }}</h2><p>{{ selectedProject.description || '暂无描述' }}</p></div></div><div><button class="ghost" @click="openProjectForm(selectedProject)">编辑</button><button class="danger-link" @click="removeProject(selectedProject)">删除</button></div></div>
            <div class="detail-tabs"><button :class="{ active: projectDetailTab==='release' }" @click="projectDetailTab='release'">发布准备</button><button :class="{ active: projectDetailTab==='sources' }" @click="projectDetailTab='sources'">源码与应用镜像</button></div>
            <template v-if="projectDetailTab==='release'">
              <div class="release-flow"><span class="done">1 选择项目</span><span :class="{done:projectReadiness(selectedProject).version}">2 确认版本</span><span :class="{done:projectReadiness(selectedProject).ready}">3 准备检查</span><span>4 确认构建</span><span>5 任务中心</span></div>
              <dl class="info-list"><dt>应用标识</dt><dd class="mono">{{ selectedProject.appKey }}</dd><dt>目标平台</dt><dd><span class="pill">{{ selectedProject.targetOs }} · {{ selectedProject.targetArch }}</span></dd><dt>项目基准版本</dt><dd><b>v{{ selectedProject.currentVersion || '未设置' }}</b></dd><dt>本次发布版本</dt><dd><b>v{{ projectReadiness(selectedProject).version || '待准备' }}</b></dd></dl>
              <div class="readiness-board"><div class="readiness-heading"><div><h3>交付准备度</h3><p>缺失项可以直接进入对应操作，全部通过后创建发布。</p></div><strong>{{ projectDeliveryChecks(selectedProject).filter(check => check.ready).length }} / {{ projectDeliveryChecks(selectedProject).length }}</strong></div><div class="project-checks"><div v-for="check in projectDeliveryChecks(selectedProject)" :key="check.key" :class="{missing:!check.ready}"><i>{{ check.ready ? '✓' : '!' }}</i><span><b>{{ check.label }}</b><small>{{ check.detail }}</small></span><button v-if="!check.ready" class="text-btn" @click="handleDeliveryCheck(selectedProject,check)">去处理</button><em v-else>已就绪</em></div></div></div>
              <div class="release-action"><span v-if="projectReadiness(selectedProject).ready">所有交付素材已经准备完成</span><span v-else>完成上方缺失项后即可构建</span><button class="primary" @click="startDelivery(selectedProject)">{{ projectReadiness(selectedProject).ready ? '确认并创建发布' : '继续处理缺失项' }}</button></div>
            </template>
            <template v-else>
              <dl class="info-list"><dt>应用标识</dt><dd class="mono">{{ selectedProject.appKey }}</dd><dt>健康路径</dt><dd class="mono">{{ selectedProject.backendHealthPath }} / {{ selectedProject.frontendHealthPath }}</dd><dt>Registry</dt><dd>由项目标识自动发现</dd><dt>镜像版本</dt><dd>{{ projectReleaseVersions(selectedProject.id).join(' / ') || '尚未准备' }}</dd></dl>
              <div class="source-heading"><div><h3>源码与应用镜像</h3><p>每个角色独立维护 Git 和 Registry，生成的 TAR 会进入离线制品库。</p></div><button class="text-btn" @click="projectDetailTab='release'">返回发布准备</button></div>
              <div class="profile-grid">
                <article v-for="role in ['BACKEND','FRONTEND']" :key="role" class="profile-card">
                  <div class="profile-top"><span>{{ role === 'BACKEND' ? 'B' : 'F' }}</span><div><h3>{{ role === 'BACKEND' ? '后端' : '前端' }}</h3><p v-if="repositoryFor(selectedProject, role)" class="mono" :title="repositoryFor(selectedProject, role).url">{{ repositoryFor(selectedProject, role).url }}</p><p v-else>尚未绑定 Git 仓库</p></div><button @click="openRepositoryForm(role)">{{ repositoryFor(selectedProject, role) ? '编辑 Git' : '绑定 Git' }}</button></div>
                  <div class="image-registry-row" :class="{ emptyRegistry: !imageRegistryFor(selectedProject, role) }"><div><small>Docker 镜像路径</small><b v-if="imageRegistryFor(selectedProject, role)" class="mono">{{ imageRegistryFor(selectedProject, role).registryUrl.replace(/^https?:\/\//,'') }}/{{ imageRegistryFor(selectedProject, role).repository }}</b><b v-else>尚未发现对应仓库</b></div><button :disabled="registryBinding" @click="syncImageRegistries">{{ registryBinding ? '读取中…' : (imageRegistryFor(selectedProject, role) ? '重新读取' : '自动读取') }}</button></div>
                  <button v-if="applicationExportTask(selectedProject.id, role)" class="task-link" @click="viewImageExportTask(applicationExportTask(selectedProject.id, role))"><span><b>{{ statusNames[applicationExportTask(selectedProject.id, role).status] }}</b><small>{{ applicationExportTask(selectedProject.id, role).stage }}</small></span><em>在任务中心查看</em></button>
                  <div class="profile-services"><div v-for="a in projectApplicationArtifacts(selectedProject.id, role).slice(0,4)" :key="a.id"><b>v{{ a.version }} · {{ short(a.gitCommit,10) }}</b><small :title="a.imageReference">{{ a.sourceType === 'REGISTRY_EXPORT' ? a.imageReference : formatDate(a.createdAt) }}</small></div><div v-if="!projectApplicationArtifacts(selectedProject.id, role).length"><b>暂无应用镜像</b><small>从仓库选择，或上传 docker save TAR</small></div></div>
                  <footer class="app-image-actions"><span>{{ repositoryFor(selectedProject, role)?.ref || '未设置分支' }}</span><div><button class="text-btn" @click="openRegistryImageForm(role)">从仓库选择</button><button class="text-btn" @click="openApplicationForm(role)">上传 TAR</button></div></footer>
                </article>
              </div>
            </template>
          </div><div v-else class="detail-panel empty big">选择一个项目查看详情</div></div>
      </section>

      <section v-else-if="active === 'profiles'" class="content"><div class="section-actions"><div><h2>部署站点</h2><p>每个配置明确属于一个项目，并记录该站点当前已部署版本。</p></div><button class="primary" @click="openProfileForm()">新建部署站点</button></div><div class="profile-grid"><article v-for="profile in profiles" :key="profile.id" class="profile-card site-card"><div class="profile-top"><span>{{ profile.name.slice(0,1) }}</span><div><h3>{{ profile.name }}</h3><p>{{ profile.projectName || projects.find(project => project.id === profileProjectId(profile))?.name || '未绑定项目' }} · {{ profile.environment }}</p></div><button @click="openProfileForm(profile)">编辑</button></div><div class="site-version"><span><small>已部署版本</small><b>v{{ profileDeployedVersion(profile) || '未设置' }}</b></span><span><small>配置修订</small><b>r{{ profile.revision }}</b></span><span><small>目标平台</small><b>{{ profile.targetOs }} · {{ profile.targetArch }}</b></span></div><div class="profile-services"><div v-for="m in profile.middleware" :key="m.component"><b>{{ componentLabel(m.component) }}</b><small>{{ Object.values(m.configured || {}).every(Boolean) ? '凭据已配置' : '待配置' }}</small></div></div><footer><span>{{ profile.middleware?.length || 0 }} 个中间件</span><button class="text-btn" @click="selectedProjectId=profileProjectId(profile); projectDetailTab='release'; switchNav('projects')">查看项目发布</button></footer></article><div v-if="!profiles.length" class="empty bordered">还没有部署站点</div></div><div class="notice"><b>版本与凭据边界</b><p>应用更新包从站点“已部署版本”开始；修改密码会产生新的配置修订，构建记录会固定使用的修订号。</p></div></section>

      <section v-else-if="active === 'imageFactory'" class="content"><div class="section-actions"><div><h2>中间件镜像制作</h2><p>选择镜像版本和目标架构；提交后统一到任务中心查看阶段与日志。</p></div><button class="ghost" @click="taskTypeFilter='MIDDLEWARE_IMAGE'; switchNav('builds')">查看镜像任务</button></div><form class="panel factory-panel" @submit.prevent="createImageExport"><div class="form-grid three"><label><span>中间件</span><select v-model="imageExportForm.component"><option v-for="c in catalog" :key="c.component" :value="c.component">{{ c.displayName }} · {{ c.imageRepo }}</option></select></label><label><span>镜像版本</span><input v-model="imageExportForm.version" required placeholder="例如 8.0"></label><label><span>目标架构</span><select v-model="imageExportForm.targetArch"><option value="amd64">x86 / amd64</option><option value="arm64">ARM / arm64</option></select></label></div><div class="factory-summary"><span><small>输出</small><b>docker save TAR</b></span><span><small>存储</small><b>离线制品库</b></span><span><small>跟踪</small><b>任务中心</b></span></div><div class="modal-actions"><button class="primary">提交镜像制作任务</button></div></form></section>

      <section v-else-if="active === 'artifacts'" class="content"><div class="section-actions"><div><h2>离线制品</h2><p>按项目、架构或版本定位构建素材；应用镜像仍从对应项目内维护。</p></div><button class="primary" @click="openArtifactForm">导入基础制品</button></div><div class="artifact-toolbar"><label class="search-field"><span>⌕</span><input v-model="artifactSearch" placeholder="搜索组件、版本、Git 或文件名"></label><select v-model="artifactProjectFilter"><option value="ALL">全部项目</option><option value="GLOBAL">全局制品</option><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option></select><select v-model="artifactArchFilter"><option value="ALL">全部架构</option><option value="amd64">amd64</option><option value="arm64">arm64</option></select><span>{{ filteredArtifacts.length }} / {{ artifacts.length }}</span></div><div class="table-wrap"><table><thead><tr><th>组件 / 来源</th><th>版本</th><th>架构</th><th>项目 / Git</th><th>文件</th><th>SHA256</th><th></th></tr></thead><tbody><tr v-for="artifact in filteredArtifacts" :key="artifact.id"><td><b>{{ componentLabel(artifact.component) }}</b><small>{{ artifact.sourceType === 'REGISTRY_EXPORT' ? '镜像仓库制作' : '页面上传' }}</small></td><td><span class="version">{{ artifact.version }}</span></td><td><span class="pill muted">{{ artifact.architecture ? artifact.architecture.replace('linux/','') : '—' }}</span></td><td><span>{{ artifact.projectName || '全局制品' }}</span><small v-if="artifact.gitCommit" class="mono" :title="artifact.gitCommit">{{ artifact.gitCommit }}</small></td><td :title="artifact.imageReference || artifact.fileName">{{ artifact.fileName }}<small>{{ formatBytes(artifact.size) }}</small></td><td class="mono" :title="artifact.sha256">{{ short(artifact.sha256,16) }}…</td><td><button class="download" @click="downloadArtifact(artifact)">下载</button></td></tr></tbody></table><div v-if="!filteredArtifacts.length" class="empty">没有符合条件的制品</div></div></section>

      <section v-else-if="active === 'sqls'" class="content"><div class="section-actions"><div><h2>数据库脚本库</h2><p>上传初始化 SQL 与迁移 SQL，构建时按版本选择入包（init SQL 当前仅对 MySQL 生效）。</p></div><button class="primary" @click="openSqlForm">＋ 上传脚本</button></div><div class="filter-row"><span class="pill">{{ sqlScripts.length }} 个脚本</span></div><div class="table-wrap"><table><thead><tr><th>类型</th><th>名称</th><th>目标版本</th><th>文件</th><th>大小</th><th>SHA256</th><th>上传时间</th><th></th></tr></thead><tbody><tr v-for="s in sqlScripts" :key="s.id"><td><span class="pill" :class="s.kind==='INIT'?'':'muted'">{{ s.kind==='INIT'?'初始化':'迁移' }}</span></td><td><b>{{ s.name }}</b></td><td><span class="version">{{ s.targetVersion }}</span></td><td>{{ s.fileName }}</td><td>{{ formatBytes(s.size) }}</td><td class="mono" :title="s.sha256">{{ short(s.sha256,16) }}…</td><td>{{ formatDate(s.createdAt) }}</td><td><button class="danger-link" @click="removeSqlScript(s)">删除</button></td></tr></tbody></table><div v-if="!sqlScripts.length" class="empty">暂无数据库脚本</div></div></section>

      <section v-else class="content"><div class="section-actions"><div><h2>任务中心</h2><p>交付构建、应用镜像和中间件镜像使用同一套状态与错误恢复入口。</p></div><button class="primary" @click="startDelivery()">创建发布</button></div><div v-if="pollingWarning" class="persistent-warning"><span>{{ pollingWarning }}</span><button class="text-btn" @click="loadAll">立即重试</button></div><div class="task-filters"><select v-model="taskTypeFilter"><option value="ALL">全部任务类型</option><option value="BUILD">交付构建</option><option value="APP_IMAGE">应用镜像</option><option value="MIDDLEWARE_IMAGE">中间件镜像</option></select><select v-model="taskStatusFilter"><option value="ALL">全部状态</option><option value="RUNNING">执行中</option><option value="QUEUED">排队中</option><option value="SUCCEEDED">成功</option><option value="FAILED">失败</option></select><span>{{ filteredTasks.length }} / {{ unifiedTasks.length }} 个任务</span></div><div class="build-layout"><div class="table-wrap build-table"><table><thead><tr><th>任务</th><th>类型</th><th>状态</th><th>当前阶段</th><th>创建时间</th><th>操作</th></tr></thead><tbody><tr v-for="item in filteredTasks" :key="item.key" :class="{selected:selectedTaskKey===item.key}" @click="selectUnifiedTask(item)"><td><b>{{ taskTitle(item) }}</b><small>{{ item.projectName || item.task.imageReference }}</small></td><td>{{ taskKindLabel(item.kind) }}</td><td><span class="status" :class="item.status.toLowerCase()">{{ statusNames[item.status] }}</span></td><td><span class="stage-dot" :class="item.status.toLowerCase()"></span>{{ item.stage }}</td><td>{{ formatDate(item.createdAt) }}</td><td><div class="row-actions"><button v-if="item.status==='SUCCEEDED' && (item.kind==='BUILD' || item.task.artifactId)" class="download" @click.stop="openTaskArtifact(item)">下载</button><button v-if="item.status==='FAILED'" class="text-btn" @click.stop="retryUnifiedTask(item)">修复重试</button></div></td></tr></tbody></table><div v-if="!filteredTasks.length" class="empty">当前筛选条件下没有任务</div></div><aside class="log-panel"><div class="panel-head"><div><h3>任务详情</h3><p v-if="selectedUnifiedTask">{{ taskTitle(selectedUnifiedTask) }}</p></div><button v-if="selectedUnifiedTask" class="text-btn" @click="refreshSelectedTask">刷新</button></div><div v-if="selectedUnifiedTask" class="task-summary"><span><small>状态</small><b>{{ statusNames[selectedUnifiedTask.status] }}</b></span><span><small>任务类型</small><b>{{ taskKindLabel(selectedUnifiedTask.kind) }}</b></span><span><small>当前阶段</small><b>{{ selectedUnifiedTask.stage }}</b></span></div><pre v-if="selectedUnifiedTask">{{ selectedTaskLog || (selectedUnifiedTask.task.reused ? '已复用制品库中的现有 TAR。' : '等待日志输出…') }}</pre><div v-else class="empty">选择任务查看详情</div><div v-if="selectedTaskError(selectedUnifiedTask)" class="error-box"><b>失败原因</b><p>{{ selectedTaskError(selectedUnifiedTask) }}</p><button class="retry-button" @click="retryUnifiedTask(selectedUnifiedTask)">修复并重新发起</button></div><div v-if="selectedTaskChecksum(selectedUnifiedTask)" class="checksum"><b>SHA256</b><code>{{ selectedTaskChecksum(selectedUnifiedTask) }}</code><button class="text-btn" @click="copyChecksum(selectedTaskChecksum(selectedUnifiedTask))">复制</button></div></aside></div></section>
    </main>

    <div v-if="showProjectForm" class="modal-backdrop" @click.self="showProjectForm=false"><form class="modal" @submit.prevent="saveProject"><div class="modal-head"><div><h2>{{ projectForm.id ? '编辑项目' : '新建项目' }}</h2><p>项目基准版本用于新建站点的初始版本；各站点部署版本在部署配置中维护。</p></div><button type="button" @click="showProjectForm=false">×</button></div><div class="form-grid"><label><span>项目名称</span><input v-model="projectForm.name" required></label><label><span>应用标识</span><input v-model="projectForm.appKey" required placeholder="kunlun-app"></label><label><span>目标架构</span><select v-model="projectForm.targetArch" :disabled="!!projectForm.id"><option value="amd64">x86 / amd64</option><option value="arm64">ARM / arm64</option></select></label><label><span>项目基准版本</span><input v-model="projectForm.currentVersion" required placeholder="例如 1.0.0"></label><label><span>后端健康路径</span><input v-model="projectForm.backendHealthPath" required placeholder="/actuator/health"></label><label><span>前端健康路径</span><input v-model="projectForm.frontendHealthPath" required placeholder="/"></label><label class="full"><span>描述</span><textarea v-model="projectForm.description" rows="3"></textarea></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showProjectForm=false">取消</button><button class="primary">保存</button></div></form></div>

    <div v-if="showRepositoryForm" class="modal-backdrop" @click.self="showRepositoryForm=false"><form class="modal" @submit.prevent="saveRepository"><div class="modal-head"><div><h2>绑定{{ repositoryForm.role === 'BACKEND' ? '后端' : '前端' }} Git 仓库</h2><p>仓库用于建立镜像与源代码提交之间的追踪关系。</p></div><button type="button" @click="showRepositoryForm=false">×</button></div><div class="form-grid"><label class="full"><span>仓库地址</span><input v-model="repositoryForm.url" required placeholder="https://git.example.com/team/app.git"></label><label><span>默认分支 / Ref</span><input v-model="repositoryForm.ref" required placeholder="main"></label><label><span>认证方式</span><select v-model="repositoryForm.authType"><option value="NONE">无需认证</option><option value="HTTPS">HTTPS Token</option><option value="SSH">SSH 私钥</option></select></label><label v-if="repositoryForm.authType!=='NONE'"><span>用户名</span><input v-model="repositoryForm.username"></label><label v-if="repositoryForm.authType!=='NONE'"><span>{{ repositoryForm.authType==='SSH' ? 'SSH 私钥' : 'Token / 密码' }}</span><input v-model="repositoryForm.secret" type="password" :required="!repositoryForm.id"></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showRepositoryForm=false">取消</button><button class="primary">保存绑定</button></div></form></div>

    <div v-if="showRegistryImageForm" class="modal-backdrop" @click.self="showRegistryImageForm=false"><form class="modal wide" @submit.prevent="createApplicationRegistryExport"><div class="modal-head"><div><h2>选择{{ registryImageForm.role === 'BACKEND' ? '后端' : '前端' }}应用镜像</h2><p>镜像和元数据直接读取自 {{ registryCatalogRepository || '100.113.245.88:5000' }}，无需手工输入标签。</p></div><button type="button" @click="showRegistryImageForm=false">×</button></div><div class="credential-toolbar"><span v-if="registryTagsLoading">正在读取标签、创建时间与 Git 信息…</span><span v-else>可用镜像 {{ registryImages.length }} 个<span v-if="unavailableRegistryTags.length">；另有 {{ unavailableRegistryTags.length }} 个损坏标签已跳过</span></span></div><div class="form-grid"><label class="full"><span>镜像标签</span><select v-if="registryImages.length" v-model="registryImageForm.tag" required @change="useRegistryTag"><option v-for="image in registryImages" :key="image.tag" :value="image.tag">{{ registryImageLabel(image) }}</option></select><div v-else-if="!registryTagsLoading" class="registry-empty">没有可用镜像；请先确认 Registry 中已经推送完整的目标架构镜像。</div></label><div v-if="selectedRegistryImage" class="registry-image-meta full"><div><small>创建时间</small><b>{{ selectedRegistryImage.createdAt ? formatDate(selectedRegistryImage.createdAt) : '镜像未记录' }}</b></div><div><small>Git Commit</small><b class="mono">{{ selectedRegistryImage.gitCommit || '将读取 Git Ref' }}</b></div><div><small>平台 / 大小</small><b>{{ selectedRegistryImage.architecture }} · {{ formatBytes(selectedRegistryImage.size) }}</b></div><div><small>Digest</small><b class="mono" :title="selectedRegistryImage.digest">{{ short(selectedRegistryImage.digest, 22) }}…</b></div></div><label><span>应用版本</span><input v-model="registryImageForm.version" required pattern="[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?" placeholder="1.2.3"></label><label><span>Git Commit ID（自动）</span><div class="inline-field"><input v-model="registryImageForm.gitCommit" readonly required placeholder="正在读取 Git…"><button type="button" class="text-btn" :disabled="registryCommitLoading" @click="refreshRegistryCommit(true)">{{ registryCommitLoading ? '获取中…' : '读取 Git Ref' }}</button></div><small>{{ registryCommitSource || '优先使用镜像 OCI 信息和 sha-* 标签' }}</small></label><label class="full"><span>目标平台</span><input :value="`${registryImageForm.targetOs} · linux/${registryImageForm.targetArch}`" disabled></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showRegistryImageForm=false">取消</button><button class="primary" :disabled="registryTagsLoading || registryCommitLoading || !selectedRegistryImage || !registryImageForm.gitCommit">拉取并生成离线 TAR</button></div></form></div>

    <div v-if="showApplicationForm" class="modal-backdrop" @click.self="showApplicationForm=false"><form class="modal" @submit.prevent="importApplicationArtifact"><div class="modal-head"><div><h2>上传{{ applicationForm.role === 'BACKEND' ? '后端' : '前端' }}应用镜像</h2><p>Git Commit 会从镜像文件名中的 sha-* 或项目绑定的 Git Ref 自动获取。</p></div><button type="button" @click="showApplicationForm=false">×</button></div><div class="form-grid"><label><span>应用版本</span><input v-model="applicationForm.version" required placeholder="1.2.3"></label><label><span>Git Commit ID（自动）</span><div class="inline-field"><input v-model="applicationForm.gitCommit" readonly required placeholder="正在读取 Git…"><button type="button" class="text-btn" :disabled="applicationCommitLoading" @click="refreshApplicationCommit(true)">{{ applicationCommitLoading ? '获取中…' : '重新获取' }}</button></div><small>{{ applicationCommitSource || '根据镜像文件名或绑定仓库自动获取' }}</small></label><label class="full"><span>镜像 TAR</span><input type="file" accept=".tar" @change="useApplicationFile($event.target.files[0])" required></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showApplicationForm=false">取消</button><button class="primary" :disabled="applicationCommitLoading || !applicationForm.gitCommit">上传并绑定</button></div></form></div>

    <div v-if="showSqlForm" class="modal-backdrop" @click.self="showSqlForm=false"><form class="modal" @submit.prevent="importSqlScript"><div class="modal-head"><div><h2>上传数据库脚本</h2><p>初始化 SQL 随 bootstrap 包入 database/init；迁移 SQL 入 database/migrations/目标版本。</p></div><button type="button" @click="showSqlForm=false">×</button></div><div class="form-grid"><label><span>类型</span><select v-model="sqlForm.kind"><option value="INIT">初始化 (INIT)</option><option value="MIGRATION">迁移 (MIGRATION)</option></select></label><label><span>名称</span><input v-model="sqlForm.name" required placeholder="schema-init"></label><label><span>目标版本</span><input v-model="sqlForm.targetVersion" required placeholder="1.1.1"></label><label class="full"><span>SQL 文件</span><input type="file" accept=".sql" @change="sqlForm.file=$event.target.files[0]" required></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showSqlForm=false">取消</button><button class="primary">上传并校验</button></div></form></div>

    <div v-if="showProfileForm" class="modal-backdrop" @click.self="showProfileForm=false"><form class="modal profile-modal" @submit.prevent="saveProfile"><div class="modal-head"><div><h2>{{ profileForm.id ? '编辑部署站点' : '新建部署站点' }}</h2><p>一个站点只属于一个项目；已部署版本决定应用更新包的起始版本。</p></div><button type="button" @click="showProfileForm=false">×</button></div><div class="form-grid three"><label><span>所属项目</span><select v-model="profileForm.projectId" required :disabled="profileForm.projectLocked" @change="syncProfileProject"><option value="" disabled>请选择项目</option><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option></select><small v-if="profileForm.id && !profileForm.projectLocked">旧配置需要补充所属项目，保存后不可更改</small></label><label><span>站点名称</span><input v-model="profileForm.name" required placeholder="例如 北京生产站点"></label><label><span>已部署版本</span><input v-model="profileForm.deployedVersion" required placeholder="例如 1.0.0"></label><label><span>环境</span><input v-model="profileForm.environment"></label><label><span>目标平台</span><input :value="`${profileForm.targetOs} · linux/${profileForm.targetArch}`" disabled></label><label><span>前端端口</span><input v-model.number="profileForm.frontendPort" type="number"></label><label><span>时区</span><input v-model="profileForm.timezone"></label><label class="full"><span>Java 参数</span><input v-model="profileForm.javaOptions"></label></div><div class="credential-toolbar"><span>选择该站点需要的中间件，每个组件使用独立密码</span><button type="button" class="ghost" @click="generateSecrets">一键生成全部强密码</button></div><div class="form-section"><b>中间件</b><small>按需勾选</small></div><div class="mw-picker" v-for="[category, items] in catalogByCategory" :key="category"><small class="mw-cat">{{ category }}</small><label class="chip" v-for="c in items" :key="c.component"><input type="checkbox" :checked="middlewareSelected(c.component)" @change="toggleMiddleware(c.component)"><span>{{ c.displayName }}</span></label></div><div v-for="m in profileForm.middleware" :key="m.component" class="mw-creds"><div class="form-section"><b>{{ componentLabel(m.component) }}</b></div><div class="form-grid three"><label v-for="cred in (catalogEntry(m.component)?.credentials || [])" :key="cred.key"><span>{{ cred.label }}</span><input v-if="cred.secret" v-model="m.credentials[cred.key]" :required="!profileForm.id && cred.required" type="password"><input v-else v-model="m.credentials[cred.key]"></label></div></div><div class="modal-actions"><button type="button" class="ghost" @click="showProfileForm=false">取消</button><button class="primary">保存部署站点</button></div></form></div>

    <div v-if="showArtifactForm" class="modal-backdrop" @click.self="showArtifactForm=false"><form class="modal" @submit.prevent="importArtifact"><div class="modal-head"><div><h2>导入离线制品</h2><p>上传 tar/tgz，平台计算 SHA256 并入库。</p></div><button type="button" @click="showArtifactForm=false">×</button></div><div class="form-grid"><label><span>组件</span><select v-model="artifactForm.component"><option v-for="c in importableComponents" :key="c.component" :value="c.component">{{ c.displayName }}</option></select></label><label><span>架构</span><select v-model="artifactForm.arch"><option value="amd64">amd64 (x86_64)</option><option value="arm64">arm64 (aarch64)</option></select></label><label><span>版本</span><input v-model="artifactForm.version" required></label><label class="full"><span>制品文件</span><input type="file" @change="artifactForm.file=$event.target.files[0]" required></label></div><div class="modal-actions"><button type="button" class="ghost" @click="showArtifactForm=false">取消</button><button class="primary">上传并校验</button></div></form></div>

    <div v-if="showBuildForm" class="modal-backdrop" @click.self="showBuildForm=false">
      <form class="modal build-modal" @submit.prevent="createBuild">
        <div class="modal-head"><div><h2>确认并创建发布</h2><p>平台已经按项目、版本和站点自动匹配制品，提交前只需确认结果。</p></div><button type="button" aria-label="关闭" @click="showBuildForm=false">×</button></div>
        <div class="package-tabs" role="radiogroup" aria-label="交付类型"><label :class="{ active: buildForm.packageType === 'BOOTSTRAP' }"><input v-model="buildForm.packageType" type="radio" value="BOOTSTRAP" @change="changePackageType"><b>完整初始化包</b><small>新环境首次安装，只设置目标版本</small></label><label :class="{ active: buildForm.packageType === 'APP_UPDATE' }"><input v-model="buildForm.packageType" type="radio" value="APP_UPDATE" @change="changePackageType"><b>应用更新包</b><small>已部署环境从当前版本升级</small></label></div>
        <div class="build-form-section"><div class="build-section-title"><span>1</span><div><b>发布信息</b><small>项目决定目标平台，部署站点决定已部署版本</small></div></div><div class="form-grid three"><label><span>项目</span><select v-model="buildForm.projectId" required @change="syncBuildProject"><option value="" disabled>请选择项目</option><option v-for="p in projects" :key="p.id" :value="p.id">{{ p.name }}</option></select></label><label><span>部署站点</span><select v-model="buildForm.profileId" required @change="syncBuildProfile"><option value="" disabled>请选择当前项目的站点</option><option v-for="p in compatibleProfiles" :key="p.id" :value="p.id">{{ p.name }} · 已部署 v{{ profileDeployedVersion(p) }} · r{{ p.revision }}</option></select><small v-if="!compatibleProfiles.length">当前项目还没有部署站点</small></label><label><span>目标平台</span><input :value="`${buildForm.targetOs} · linux/${buildForm.targetArch}`" disabled></label><label v-if="buildForm.packageType==='APP_UPDATE'"><span>站点已部署版本</span><input v-model="buildForm.fromVersion" readonly required></label><label><span>本次发布版本</span><input v-model="buildForm.targetVersion" required placeholder="例如 1.0.1"></label><label v-if="buildForm.packageType==='BOOTSTRAP'"><span>同版本包修订号</span><input v-model="buildForm.packageRevision" placeholder="r1"></label><div v-if="buildForm.packageType==='APP_UPDATE'" class="full scope"><span>更新内容</span><label><input v-model="buildForm.updateScope" type="checkbox" value="BACKEND"> 后端应用</label><label><input v-model="buildForm.updateScope" type="checkbox" value="FRONTEND"> 前端应用</label></div></div></div>
        <div class="build-form-section"><div class="build-section-title"><span>2</span><div><b>交付准备检查</b><small>{{ activeBuildReadiness.filter(item => item.ready).length }}/{{ activeBuildReadiness.length }} 项制品已自动匹配</small></div></div><div class="readiness-list"><div v-for="item in activeBuildReadiness" :key="item.component" class="readiness-row" :class="{ missing: !item.ready }"><i>{{ item.ready ? '✓' : '!' }}</i><span><b>{{ componentLabel(item.component) }}</b><small v-if="item.artifact">v{{ item.artifact.version }} · {{ item.artifact.gitCommit ? `Git ${short(item.artifact.gitCommit,10)} · ` : '' }}{{ formatBytes(item.artifact.size) }}</small><small v-else>没有找到当前项目、版本和架构可用的制品</small></span><select v-if="item.options.length > 1" v-model="buildForm.artifactSelection[item.component]"><option v-for="artifact in item.options" :key="artifact.id" :value="artifact.id">v{{ artifact.version }} · {{ artifact.gitCommit ? short(artifact.gitCommit,10) : formatBytes(artifact.size) }}</option></select><em v-else-if="item.ready">自动匹配</em><button v-else type="button" class="text-btn" @click="openMissingArtifact(item)">去准备</button></div></div></div>
        <div class="build-form-section compact"><div class="build-section-title"><span>3</span><div><b>数据库脚本</b><small>仅在需要时纳入交付包</small></div></div><label v-if="buildForm.packageType==='BOOTSTRAP'" class="script-select"><span>初始化 SQL（可选）</span><select v-model="buildForm.dbInitSqlIds" multiple size="3"><option v-for="s in initSqlOptions" :key="s.id" :value="s.id">{{ s.name }} · {{ s.fileName }} · v{{ s.targetVersion }}</option></select></label><div class="migration-toggle"><label><input v-model="buildForm.dbMigrationRequired" type="checkbox"> 本版本需要数据库迁移</label></div><label v-if="buildForm.dbMigrationRequired" class="script-select"><span>迁移 SQL（目标版本 {{ buildForm.targetVersion }}）</span><select v-model="buildForm.dbMigrationSqlIds" multiple size="3"><option v-for="s in migrationSqlOptions" :key="s.id" :value="s.id">{{ s.name }} · {{ s.fileName }}</option></select><small v-if="!migrationSqlOptions.length">当前目标版本没有迁移脚本，请先到数据库脚本页上传。</small></label></div>
        <div class="modal-actions build-submit"><span :style="{ color: buildCanSubmit ? '#19715f' : '#9a642e' }">{{ buildCanSubmit ? '检查通过，可以提交构建' : '还有必填信息或制品未准备完成' }}</span><button type="button" class="ghost" @click="showBuildForm=false">取消</button><button class="primary" :disabled="!buildCanSubmit">提交构建</button></div>
      </form>
    </div>

    <transition name="toast"><div v-if="toast.show" class="toast" :class="toast.type">{{ toast.type === 'error' ? '!' : '✓' }} {{ toast.text }}</div></transition><div v-if="loading" class="loading-bar"></div>
  </div>
</template>
