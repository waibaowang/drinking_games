<script setup lang="ts">
import { reactive, ref } from 'vue'
import { api, refreshCsrf } from '../api'
import type { UserView } from '../types'

const emit = defineEmits<{ authenticated: [user: UserView] }>()
const busy = ref(false)
const error = ref('')
const form = reactive({ username: '', password: '' })

async function submit() {
  busy.value = true
  error.value = ''
  try {
    const user = await api<UserView>('/api/auth/login', { method: 'POST', body: JSON.stringify(form) })
    await refreshCsrf()
    emit('authenticated', user)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '登录失败'
  } finally { busy.value = false }
}
</script>

<template>
  <main class="auth-layout">
    <section class="auth-intro">
      <p class="eyebrow">PRIVATE GAME NIGHT</p>
      <h1>今晚，<br />一起开局。</h1>
      <p>开一个只属于朋友的房间。人少玩飞行棋，人多开德州，没有陌生人，只有笑声、判断和一点点运气。</p>
      <div class="feature-row"><span>2—9人</span><span>实时游戏</span><span>私人房间</span></div>
    </section>
    <section class="auth-card panel">
      <div class="brand-chip">H</div>
      <h2>回到游戏</h2>
      <p class="muted">账号由管理员创建，请使用分配给你的账号登录</p>
      <form @submit.prevent="submit">
        <label>用户名<input v-model.trim="form.username" required autocomplete="username" /></label>
        <label>密码<input v-model="form.password" type="password" required autocomplete="current-password" /></label>
        <p v-if="error" class="error-banner">{{ error }}</p>
        <button class="primary full" :disabled="busy">{{ busy ? '请稍候…' : '进入河牌会' }}</button>
      </form>
    </section>
  </main>
</template>
