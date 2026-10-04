<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import type { RoomState, RoomSummary, UserView } from '../types'

defineProps<{ user: UserView }>()
const emit = defineEmits<{ enter: [room: RoomState]; logout: [] }>()
const rooms = ref<RoomSummary[]>([])
const showCreate = ref(false)
const busy = ref(false)
const error = ref('')
const join = reactive({ code: '', password: '' })
const form = reactive({ name: '今晚的牌局', password: '', smallBlind: 10, bigBlind: 20, initialStack: 2000, maxPlayers: 9, actionSeconds: 30 })

async function loadRooms() {
  rooms.value = await api<RoomSummary[]>('/api/rooms')
}
async function createRoom() {
  busy.value = true; error.value = ''
  try {
    const room = await api<RoomState>('/api/rooms', { method: 'POST', body: JSON.stringify(form) })
    emit('enter', room)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '创建失败' }
  finally { busy.value = false }
}
async function joinRoom() {
  busy.value = true; error.value = ''
  try {
    const room = await api<RoomState>('/api/rooms/join', { method: 'POST', body: JSON.stringify(join) })
    emit('enter', room)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '加入失败' }
  finally { busy.value = false }
}
async function joinListed(room: RoomSummary) {
  join.code = room.code
  if (room.locked && !join.password) { error.value = '请先在邀请码旁输入房间密码'; return }
  await joinRoom()
}
onMounted(loadRooms)
</script>

<template>
  <main class="lobby shell">
    <header class="topbar">
      <div class="wordmark"><span class="brand-chip small">H</span><div><strong>河牌会</strong><small>FRIENDS ONLY</small></div></div>
      <div class="profile"><span>你好，{{ user.displayName }}</span><button class="ghost" @click="emit('logout')">退出</button></div>
    </header>
    <section class="lobby-hero">
      <div><p class="eyebrow">LOBBY</p><h1>找张桌子，<br />等朋友入座。</h1></div>
      <button class="primary create-button" @click="showCreate = !showCreate">{{ showCreate ? '收起设置' : '＋ 开一张新桌' }}</button>
    </section>

    <section v-if="showCreate" class="create-panel panel">
      <div class="section-heading"><div><h2>新建私人牌桌</h2><p class="muted">创建后把六位邀请码发给朋友</p></div></div>
      <div class="form-grid">
        <label>房间名称<input v-model.trim="form.name" maxlength="30" /></label>
        <label>房间密码（可选）<input v-model="form.password" maxlength="32" type="password" /></label>
        <label>小盲<input v-model.number="form.smallBlind" type="number" min="1" /></label>
        <label>大盲<input v-model.number="form.bigBlind" type="number" :min="form.smallBlind * 2" /></label>
        <label>初始筹码<input v-model.number="form.initialStack" type="number" :min="form.bigBlind * 10" /></label>
        <label>最多人数<select v-model.number="form.maxPlayers"><option v-for="n in 8" :key="n" :value="n + 1">{{ n + 1 }} 人</option></select></label>
        <label>行动时间<select v-model.number="form.actionSeconds"><option :value="20">20 秒</option><option :value="30">30 秒</option><option :value="45">45 秒</option><option :value="60">60 秒</option></select></label>
      </div>
      <button class="primary" :disabled="busy" @click="createRoom">创建牌桌</button>
    </section>

    <section class="join-strip panel">
      <div><strong>收到朋友的邀请码？</strong><span class="muted">输入六位代码即可加入</span></div>
      <input v-model.trim="join.code" class="code-input" maxlength="6" placeholder="桌号" @input="join.code = join.code.toUpperCase()" />
      <input v-model="join.password" type="password" placeholder="密码（如有）" />
      <button class="secondary" :disabled="join.code.length !== 6 || busy" @click="joinRoom">加入</button>
    </section>
    <p v-if="error" class="error-banner">{{ error }}</p>

    <section class="room-section">
      <div class="section-heading"><div><p class="eyebrow">OPEN TABLES</p><h2>正在等待的牌桌</h2></div><button class="icon-button" aria-label="刷新" @click="loadRooms">↻</button></div>
      <div v-if="rooms.length" class="room-grid">
        <article v-for="room in rooms" :key="room.id" class="room-card panel">
          <div class="room-card-top"><span class="status-dot"></span><span>{{ room.locked ? '上锁' : '可加入' }}</span><strong>#{{ room.code }}</strong></div>
          <h3>{{ room.name }}</h3>
          <div class="room-stats"><span><small>盲注</small>{{ room.smallBlind }} / {{ room.bigBlind }}</span><span><small>玩家</small>{{ room.members }} / {{ room.maxPlayers }}</span></div>
          <button class="secondary full" @click="joinListed(room)">坐上这桌</button>
        </article>
      </div>
      <div v-else class="empty-state panel"><div class="empty-card">♠</div><h3>大厅还很安静</h3><p>开第一张桌，然后把邀请码发给朋友。</p></div>
    </section>
  </main>
</template>

