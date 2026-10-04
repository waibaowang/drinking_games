<script setup lang="ts">
import { reactive, ref } from 'vue'
import { api } from '../api'
import type { UserView } from '../types'

const props = defineProps<{ user: UserView }>()
const emit = defineEmits<{ updated: [user: UserView] }>()
const profile = reactive({ displayName: props.user.displayName })
const password = reactive({ currentPassword: '', newPassword: '' })
const message = ref('')
const error = ref('')

async function saveProfile() {
  error.value = ''; message.value = ''
  try { const user = await api<UserView>('/api/profile', { method: 'PATCH', body: JSON.stringify(profile) }); emit('updated', user); message.value = '昵称已更新' }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败' }
}
async function savePassword() {
  error.value = ''; message.value = ''
  try { await api<void>('/api/profile/password', { method: 'POST', body: JSON.stringify(password) }); password.currentPassword = ''; password.newPassword = ''; message.value = '密码已更新' }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '修改失败' }
}
</script>

<template>
  <section class="page-section narrow-page">
    <div class="page-heading"><div><p class="eyebrow">PROFILE</p><h1>个人资料</h1></div></div>
    <p v-if="message" class="success-banner">{{ message }}</p><p v-if="error" class="error-banner">{{ error }}</p>
    <div class="settings-grid">
      <form class="settings-card panel" @submit.prevent="saveProfile"><h2>牌桌昵称</h2><p class="muted">用户名 {{ user.username }} · {{ user.role === 'ADMIN' ? '管理员' : '玩家' }}</p><label>昵称<input v-model.trim="profile.displayName" required maxlength="20" /></label><button class="primary">保存昵称</button></form>
      <form class="settings-card panel" @submit.prevent="savePassword"><h2>修改密码</h2><label>当前密码<input v-model="password.currentPassword" type="password" required autocomplete="current-password" /></label><label>新密码<input v-model="password.newPassword" type="password" required minlength="8" maxlength="72" autocomplete="new-password" /></label><button class="primary">更新密码</button></form>
    </div>
  </section>
</template>
