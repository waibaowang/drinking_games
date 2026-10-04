<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api, ApiError } from './api'
import type { RoomState, UserView } from './types'
import AdminView from './components/AdminView.vue'
import AuthPanel from './components/AuthPanel.vue'
import HistoryView from './components/HistoryView.vue'
import LobbyView from './components/LobbyView.vue'
import MyRoomsView from './components/MyRoomsView.vue'
import PokerTable from './components/PokerTable.vue'
import FlightBoard from './components/FlightBoard.vue'
import ProfileView from './components/ProfileView.vue'

type Page = 'lobby' | 'games' | 'history' | 'profile' | 'admin'
const pages: Page[] = ['lobby', 'games', 'history', 'profile', 'admin']
const user = ref<UserView | null>(null)
const room = ref<RoomState | null>(null)
const page = ref<Page>('lobby')
const ready = ref(false)

function requestedPage(): Page {
  const value = new URLSearchParams(location.search).get('page') as Page | null
  return value && pages.includes(value) ? value : 'lobby'
}
function writeUrl(next: Page, roomId?: string, replace = false) {
  const params = new URLSearchParams()
  params.set('page', next)
  if (roomId) params.set('room', roomId)
  if (replace) history.replaceState({}, '', `?${params}`)
  else history.pushState({}, '', `?${params}`)
}
async function restoreUrl() {
  if (!user.value) return
  const params = new URLSearchParams(location.search)
  const roomId = params.get('room')
  if (roomId) {
    try { room.value = await api<RoomState>(`/api/rooms/${roomId}`); return }
    catch (cause) {
      room.value = null; page.value = 'games'
      if (cause instanceof ApiError && cause.status < 500 && cause.status !== 401) writeUrl('games', undefined, true)
      return
    }
  }
  room.value = null
  page.value = requestedPage()
  if (page.value === 'admin' && user.value.role !== 'ADMIN') { page.value = 'lobby'; writeUrl('lobby', undefined, true) }
}
function navigate(next: Page) {
  room.value = null
  page.value = next
  writeUrl(next)
}
function enter(next: RoomState) {
  room.value = next
  writeUrl('games', next.id)
}
function leaveTable() {
  room.value = null
  page.value = 'games'
  writeUrl('games')
}
async function authenticated(next: UserView) { user.value = next; await restoreUrl() }
async function logout() {
  await api<void>('/api/auth/logout', { method: 'POST' })
  user.value = null; room.value = null; writeUrl('lobby', undefined, true)
}
function expire() { user.value = null; room.value = null }
async function pop() { await restoreUrl() }

onMounted(async () => {
  window.addEventListener('popstate', pop)
  window.addEventListener('session-expired', expire)
  try { user.value = await api<UserView>('/api/auth/me'); await restoreUrl() } catch { user.value = null }
  ready.value = true
})
onBeforeUnmount(() => { window.removeEventListener('popstate', pop); window.removeEventListener('session-expired', expire) })
</script>

<template>
  <div v-if="!ready" class="splash"><span class="brand-chip">H</span><p>正在准备牌桌…</p></div>
  <AuthPanel v-else-if="!user" @authenticated="authenticated" />
  <PokerTable v-else-if="room?.gameType === 'POKER'" :initial-room="room" :user="user" @leave="leaveTable" />
  <FlightBoard v-else-if="room?.gameType === 'FLIGHT'" :initial-room="room" :user="user" @leave="leaveTable" />
  <main v-else class="app-shell shell">
    <header class="topbar app-header">
      <button class="wordmark plain-button" @click="navigate('lobby')"><span class="brand-chip small">H</span><span><strong>河牌会</strong><small>FRIENDS ONLY</small></span></button>
      <nav class="app-nav"><button :class="{ active: page === 'lobby' }" @click="navigate('lobby')">大厅</button><button :class="{ active: page === 'games' }" @click="navigate('games')">我的牌局</button><button :class="{ active: page === 'history' }" @click="navigate('history')">历史</button><button :class="{ active: page === 'profile' }" @click="navigate('profile')">资料</button><button v-if="user.role === 'ADMIN'" :class="{ active: page === 'admin' }" @click="navigate('admin')">管理</button></nav>
      <div class="profile"><span>{{ user.displayName }}</span><button class="ghost" @click="logout">退出</button></div>
    </header>
    <LobbyView v-if="page === 'lobby'" @enter="enter" />
    <MyRoomsView v-else-if="page === 'games'" @enter="enter" />
    <HistoryView v-else-if="page === 'history'" />
    <ProfileView v-else-if="page === 'profile'" :user="user" @updated="user = $event" />
    <AdminView v-else-if="page === 'admin' && user.role === 'ADMIN'" />
  </main>
</template>
