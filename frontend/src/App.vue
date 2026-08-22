<script setup>
import { reactive, ref } from 'vue'
import axios from 'axios'

const services = reactive([
  { key: 'mysql', name: 'MySQL', icon: '🗄️', status: 'idle', message: '' },
  { key: 'redis', name: 'Redis', icon: '⚡', status: 'idle', message: '' },
  { key: 'rabbitmq', name: 'RabbitMQ', icon: '🐰', status: 'idle', message: '' },
  { key: 'minio', name: 'MinIO', icon: '🪣', status: 'idle', message: '' }
])

const testingAll = ref(false)

function statusText(status) {
  return { idle: '未检测', testing: '检测中', ok: '正常', fail: '异常' }[status] || status
}

function setResult(key, data) {
  const s = services.find((x) => x.key === key)
  s.status = data.ok ? 'ok' : 'fail'
  s.message = data.message + (data.costMs != null ? `（${data.costMs} ms）` : '')
}

async function testOne(item) {
  item.status = 'testing'
  item.message = '检测中…'
  try {
    const { data } = await axios.get(`/api/health/${item.key}`)
    setResult(item.key, data)
  } catch (e) {
    item.status = 'fail'
    item.message = '请求失败: ' + (e.response?.data?.message || e.message)
  }
}

async function testAll() {
  testingAll.value = true
  services.forEach((s) => {
    s.status = 'testing'
    s.message = '检测中…'
  })
  try {
    const { data } = await axios.get('/api/health/all')
    data.results.forEach((r) => setResult(r.service, r))
  } catch (e) {
    services.forEach((s) => {
      s.status = 'fail'
      s.message = '请求失败: ' + e.message
    })
  } finally {
    testingAll.value = false
  }
}
</script>

<template>
  <div class="page">
    <header class="header">
      <h1>中间件联通性测试</h1>
      <p class="sub">后端 Spring Boot 3 · 前端 Vue 3 · MySQL / Redis / RabbitMQ / MinIO</p>
      <button class="btn btn-all" :disabled="testingAll" @click="testAll">
        {{ testingAll ? '检测中…' : '🚀 测试全部' }}
      </button>
    </header>

    <div class="grid">
      <div v-for="item in services" :key="item.key" class="card" :class="'card-' + item.status">
        <div class="card-head">
          <span class="icon">{{ item.icon }}</span>
          <span class="name">{{ item.name }}</span>
          <span class="badge" :class="'badge-' + item.status">{{ statusText(item.status) }}</span>
        </div>
        <p class="message">{{ item.message || '点击下方按钮开始检测' }}</p>
        <button class="btn" :disabled="item.status === 'testing'" @click="testOne(item)">
          {{ item.status === 'testing' ? '检测中…' : '测试' }}
        </button>
      </div>
    </div>
  </div>
</template>