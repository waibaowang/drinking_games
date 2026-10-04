<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from './api'
import type { RoomState, UserView } from './types'
import AuthPanel from './components/AuthPanel.vue'
import LobbyView from './components/LobbyView.vue'
import PokerTable from './components/PokerTable.vue'

const user = ref<UserView | null>(null)
const room = ref<RoomState | null>(null)
const ready = ref(false)

onMounted(async () => {
  try { user.value = await api<UserView>('/api/auth/me') } catch { user.value = null }
  ready.value = true
})

async function logout() {
  await api<void>('/api/auth/logout', { method: 'POST' })
  user.value = null
  room.value = null
}
</script>

<template>
  <div v-if="!ready" class="splash"><span class="brand-chip">H</span><p>正在准备牌桌…</p></div>
  <AuthPanel v-else-if="!user" @authenticated="user = $event" />
  <PokerTable v-else-if="room" :initial-room="room" :user="user" @leave="room = null" />
  <LobbyView v-else :user="user" @enter="room = $event" @logout="logout" />
</template>
