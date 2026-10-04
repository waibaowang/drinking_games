<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import type { RoomSummary, UserView } from '../types'

const users = ref<UserView[]>([])
const rooms = ref<RoomSummary[]>([])
const createForm = reactive({ username: '', displayName: '', password: '' })
const reset = reactive({ username: '', password: '' })
const error = ref('')
const message = ref('')

async function load() {
  error.value = ''
  try { [users.value, rooms.value] = await Promise.all([api<UserView[]>('/api/admin/users'), api<RoomSummary[]>('/api/admin/rooms')]) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '加载管理数据失败' }
}
async function createUser() {
  try { await api<UserView>('/api/admin/users', { method: 'POST', body: JSON.stringify(createForm) }); createForm.username = ''; createForm.displayName = ''; createForm.password = ''; message.value = '玩家账号已创建'; await load() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '创建失败' }
}
async function toggle(user: UserView) {
  try { await api<UserView>(`/api/admin/users/${encodeURIComponent(user.username)}`, { method: 'PATCH', body: JSON.stringify({ enabled: !user.enabled }) }); await load() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '更新失败' }
}
async function resetPassword() {
  try { await api<void>(`/api/admin/users/${encodeURIComponent(reset.username)}/reset-password`, { method: 'POST', body: JSON.stringify({ password: reset.password }) }); message.value = `已重置 ${reset.username} 的密码`; reset.username = ''; reset.password = '' }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '重置失败' }
}
async function closeRoom(id: string) {
  try { await api<void>(`/api/admin/rooms/${id}/close`, { method: 'POST' }); message.value = '房间已关闭'; await load() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '关闭失败' }
}
onMounted(load)
</script>

<template>
  <section class="page-section">
    <div class="page-heading"><div><p class="eyebrow">ADMINISTRATION</p><h1>管理后台</h1></div><button class="ghost" @click="load">刷新</button></div>
    <p v-if="message" class="success-banner">{{ message }}</p><p v-if="error" class="error-banner">{{ error }}</p>
    <div class="admin-grid">
      <section class="settings-card panel"><h2>创建玩家账号</h2><form @submit.prevent="createUser"><label>用户名<input v-model.trim="createForm.username" required minlength="3" maxlength="20" pattern="[A-Za-z0-9_]+" /></label><label>昵称<input v-model.trim="createForm.displayName" maxlength="20" /></label><label>临时密码<input v-model="createForm.password" type="password" required minlength="8" maxlength="72" /></label><button class="primary">创建账号</button></form></section>
      <section class="settings-card panel"><h2>重置玩家密码</h2><form @submit.prevent="resetPassword"><label>玩家<select v-model="reset.username" required><option value="" disabled>选择玩家</option><option v-for="entry in users.filter(item => item.role === 'PLAYER')" :key="entry.username" :value="entry.username">{{ entry.displayName }}（{{ entry.username }}）</option></select></label><label>新临时密码<input v-model="reset.password" type="password" required minlength="8" maxlength="72" /></label><button class="secondary">重置密码</button></form></section>
    </div>
    <section class="admin-section panel"><div class="section-heading"><h2>账号</h2><span class="muted">{{ users.length }} 个</span></div><div class="data-list"><div v-for="entry in users" :key="entry.username"><span><strong>{{ entry.displayName }}</strong><small>{{ entry.username }} · {{ entry.role }}</small></span><span class="state-pill" :class="{ off: !entry.enabled }">{{ entry.enabled ? '已启用' : '已禁用' }}</span><button class="ghost" :disabled="entry.role === 'ADMIN'" @click="toggle(entry)">{{ entry.enabled ? '禁用' : '启用' }}</button></div></div></section>
    <section class="admin-section panel"><div class="section-heading"><h2>活跃房间</h2><span class="muted">{{ rooms.length }} 桌</span></div><div v-if="rooms.length" class="data-list"><div v-for="entry in rooms" :key="entry.id"><span><strong>{{ entry.name }}</strong><small>{{ entry.gameType === 'FLIGHT' ? '飞行棋' : '德州扑克' }} · #{{ entry.code }} · {{ entry.members }}/{{ entry.maxPlayers }} 人<span v-if="entry.gameType === 'POKER'"> · {{ entry.smallBlind }}/{{ entry.bigBlind }}</span></small></span><button class="danger" @click="closeRoom(entry.id)">强制关闭</button></div></div><p v-else class="empty-inline">当前没有活跃房间</p></section>
  </section>
</template>
