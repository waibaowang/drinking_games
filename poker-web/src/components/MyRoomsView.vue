<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import type { RoomState, RoomSummary } from '../types'

const emit = defineEmits<{ enter: [room: RoomState] }>()
const rooms = ref<RoomSummary[]>([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true; error.value = ''
  try { rooms.value = await api<RoomSummary[]>('/api/rooms/mine') }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '加载失败' }
  finally { loading.value = false }
}
async function enter(id: string) {
  try { emit('enter', await api<RoomState>(`/api/rooms/${id}`)) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '无法进入牌桌' }
}
onMounted(load)
</script>

<template>
  <section class="page-section">
    <div class="page-heading"><div><p class="eyebrow">MY TABLES</p><h1>我的牌局</h1></div><button class="ghost" @click="load">刷新</button></div>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <div v-if="loading" class="empty-state panel">正在加载你的牌局…</div>
    <div v-else-if="rooms.length" class="room-grid">
      <article v-for="room in rooms" :key="room.id" class="room-card panel">
        <div class="room-card-top"><span class="status-dot"></span><span>{{ room.recovered ? '已从服务器恢复' : '进行中' }}</span><strong>#{{ room.code }}</strong></div>
        <h3>{{ room.name }}</h3>
        <div class="room-stats"><span><small>盲注</small>{{ room.smallBlind }} / {{ room.bigBlind }}</span><span><small>成员</small>{{ room.members }} / {{ room.maxPlayers }}</span></div>
        <button class="secondary full" @click="enter(room.id)">返回牌桌</button>
      </article>
    </div>
    <div v-else class="empty-state panel"><div class="empty-card">♠</div><h3>还没有进行中的牌局</h3><p>去大厅开一桌，或者凭朋友的邀请码加入。</p></div>
  </section>
</template>
