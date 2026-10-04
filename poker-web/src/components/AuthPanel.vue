<script setup lang="ts">
import { reactive, ref } from 'vue'
import { api, refreshCsrf } from '../api'
import type { UserView } from '../types'

const emit = defineEmits<{ authenticated: [user: UserView] }>()
const mode = ref<'login' | 'register'>('login')
const busy = ref(false)
const error = ref('')
const form = reactive({ username: '', password: '', displayName: '' })

async function submit() {
  busy.value = true
  error.value = ''
  try {
    const endpoint = mode.value === 'login' ? '/api/auth/login' : '/api/auth/register'
    const user = await api<UserView>(endpoint, { method: 'POST', body: JSON.stringify(form) })
    await refreshCsrf()
    emit('authenticated', user)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '登录失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="auth-layout">
    <section class="auth-intro">
      <p class="eyebrow">PRIVATE TEXAS HOLD'EM</p>
      <h1>今晚，<br />河牌见。</h1>
      <p>开一张只属于朋友的桌。没有充值，没有陌生人，只有筹码、判断和一点点运气。</p>
      <div class="feature-row"><span>2—9人</span><span>实时牌桌</span><span>私人房间</span></div>
    </section>
    <section class="auth-card panel">
      <div class="brand-chip">H</div>
      <h2>{{ mode === 'login' ? '回到牌桌' : '加入河牌会' }}</h2>
      <p class="muted">{{ mode === 'login' ? '使用你的账号继续游戏' : '创建一个仅用于朋友局的账号' }}</p>
      <form @submit.prevent="submit">
        <label>用户名<input v-model.trim="form.username" required minlength="3" maxlength="20" autocomplete="username" placeholder="字母、数字或下划线" /></label>
        <label v-if="mode === 'register'">牌桌昵称<input v-model.trim="form.displayName" maxlength="20" placeholder="朋友看到的名字" /></label>
        <label>密码<input v-model="form.password" type="password" required :minlength="mode === 'register' ? 8 : 1" autocomplete="current-password" placeholder="至少 8 位" /></label>
        <p v-if="error" class="error-banner">{{ error }}</p>
        <button class="primary full" :disabled="busy">{{ busy ? '请稍候…' : mode === 'login' ? '进入大厅' : '创建并进入' }}</button>
      </form>
      <button class="text-button" @click="mode = mode === 'login' ? 'register' : 'login'; error = ''">
        {{ mode === 'login' ? '第一次来？创建账号' : '已有账号？直接登录' }}
      </button>
    </section>
  </main>
</template>

