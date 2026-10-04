<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Client } from '@stomp/stompjs'
import { api } from '../api'
import type { FlightPlayerSnapshot, FlightRoomState, UserView } from '../types'

const props = defineProps<{ initialRoom: FlightRoomState; user: UserView }>()
const emit = defineEmits<{ leave: [] }>()
const room = ref(props.initialRoom)
const error = ref('')
const connected = ref(false)
const actionPending = ref(false)
const pendingVersion = ref<number | null>(null)
const recoveryDismissed = ref(false)
const notice = ref('')
const chatText = ref('')
const chats = ref<Array<{ username: string; displayName: string; text: string; sentAt: string }>>([])
let pendingTimer = 0
let client: Client | null = null
let checkingSession = false

const colors = ['#ef5350', '#f5c542', '#42a5f5', '#55bd77']
const colorNames = ['红色', '黄色', '蓝色', '绿色']
const diceFaces = ['', '⚀', '⚁', '⚂', '⚃', '⚄', '⚅']
const me = computed(() => room.value.game.players.find(player => player.id === props.user.username))
const seated = computed(() => !!me.value)
const myTurn = computed(() => me.value?.seat === room.value.game.currentSeat)
const canStart = computed(() => props.user.username === room.value.hostUsername
  && ['WAITING', 'COMPLETE'].includes(room.value.game.phase) && room.value.game.players.length >= 2)
const track = Array.from({ length: 52 }, (_, index) => pointOnCircle(index, 42))

function pointOnCircle(index: number, radius: number) {
  const angle = (90 + index * 360 / 52) * Math.PI / 180
  return { x: 50 + radius * Math.cos(angle), y: 50 + radius * Math.sin(angle) }
}
function homePoint(seat: number, step: number) {
  const angle = (90 + seat * 90) * Math.PI / 180
  const radius = 32 - step * 4.7
  return { x: 50 + radius * Math.cos(angle), y: 50 + radius * Math.sin(angle) }
}
function hangarPoint(seat: number, piece: number) {
  const centers = [{ x: 75, y: 75 }, { x: 25, y: 75 }, { x: 25, y: 25 }, { x: 75, y: 25 }]
  const offsets = [{ x: -3, y: -3 }, { x: 3, y: -3 }, { x: -3, y: 3 }, { x: 3, y: 3 }]
  return { x: centers[seat].x + offsets[piece].x, y: centers[seat].y + offsets[piece].y }
}
function piecePoint(player: FlightPlayerSnapshot, pieceIndex: number, progress: number) {
  if (progress < 0) return hangarPoint(player.seat, pieceIndex)
  if (progress >= 52) return homePoint(player.seat, progress - 52)
  const point = track[(player.seat * 13 + progress) % 52]
  const angle = pieceIndex * Math.PI / 2
  return { x: point.x + Math.cos(angle) * 1.1, y: point.y + Math.sin(angle) * 1.1 }
}
function playerAt(seat: number) { return room.value.game.players.find(player => player.seat === seat) }
function isMovable(player: FlightPlayerSnapshot, piece: number) {
  return !actionPending.value && connected.value && room.value.status === 'ACTIVE'
    && player.id === props.user.username && myTurn.value && room.value.game.phase === 'MOVE'
    && room.value.game.legalPieceIndexes.includes(piece)
}
function clearPending() {
  actionPending.value = false
  pendingVersion.value = null
  window.clearTimeout(pendingTimer)
}

async function sit(seat: number) {
  try { await api<void>(`/api/rooms/${room.value.id}/seats/${seat}`, { method: 'POST' }) }
  catch (cause) { showError(cause) }
}
async function stand() {
  try { await api<void>(`/api/rooms/${room.value.id}/seat`, { method: 'DELETE' }) }
  catch (cause) { showError(cause) }
}
async function start() {
  try { await api<void>(`/api/rooms/${room.value.id}/start`, { method: 'POST' }) }
  catch (cause) { showError(cause) }
}
function act(action: 'ROLL' | 'MOVE', pieceIndex?: number) {
  if (actionPending.value || !client?.connected || room.value.status === 'CLOSED') return
  actionPending.value = true
  pendingVersion.value = room.value.game.version
  window.clearTimeout(pendingTimer)
  pendingTimer = window.setTimeout(() => refreshState(true), 6000)
  client.publish({ destination: `/app/flight/rooms/${room.value.id}/action`, body: JSON.stringify({
    actionId: crypto.randomUUID(), action, pieceIndex, version: room.value.game.version,
  }) })
}
function sendChat() {
  const text = chatText.value.trim()
  if (!text || !client?.connected) return
  client.publish({ destination: `/app/flight/rooms/${room.value.id}/chat`, body: JSON.stringify({ text }) })
  chatText.value = ''
}
function showError(cause: unknown) {
  error.value = cause instanceof Error ? cause.message : '操作失败'
  clearPending()
  if (error.value.includes('状态已变化')) refreshState(true)
  window.setTimeout(() => error.value = '', 4000)
}
async function refreshState(settlePending = false) {
  try {
    applyIncoming(await api<FlightRoomState>(`/api/rooms/${room.value.id}`))
    if (settlePending) clearPending()
  }
  catch (cause) { showError(cause) }
}
function applyIncoming(incoming: FlightRoomState) {
  if (incoming.status === 'CLOSED' || incoming.game.version >= room.value.game.version) room.value = incoming
  if (incoming.status === 'CLOSED' || (pendingVersion.value !== null && incoming.game.version > pendingVersion.value)) {
    clearPending()
  }
}
async function copyCode() {
  try { await navigator.clipboard.writeText(room.value.code); notice.value = '邀请码已复制' }
  catch { notice.value = `邀请码：${room.value.code}` }
  window.setTimeout(() => notice.value = '', 2500)
}
async function checkSession() {
  if (checkingSession) return
  checkingSession = true
  window.setTimeout(async () => {
    try { await api<UserView>('/api/auth/me') } catch { /* api emits session-expired for 401 */ }
    finally { checkingSession = false }
  }, 700)
}
function connect() {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  client = new Client({
    brokerURL: `${scheme}://${location.host}/ws`, reconnectDelay: 3000,
    heartbeatIncoming: 10000, heartbeatOutgoing: 10000,
    onConnect: async () => {
      client?.subscribe(`/user/queue/flight-rooms/${room.value.id}`, message => applyIncoming(JSON.parse(message.body)))
      client?.subscribe('/user/queue/errors', message => showError(new Error(JSON.parse(message.body).message)))
      client?.subscribe(`/user/queue/flight-rooms/${room.value.id}/chat`, message => {
        chats.value.push(JSON.parse(message.body)); if (chats.value.length > 50) chats.value.shift()
      })
      await refreshState(true)
      connected.value = true
    },
    onStompError: () => { connected.value = false; checkSession() },
    onWebSocketClose: () => { connected.value = false; checkSession() },
  })
  client.activate()
}

onMounted(connect)
onBeforeUnmount(() => { window.clearTimeout(pendingTimer); client?.deactivate() })
</script>

<template>
  <main class="game-shell flight-shell">
    <header class="game-topbar">
      <div class="wordmark"><span class="brand-chip small flight-brand">✈</span><div><strong>{{ room.name }}</strong><button class="copy-code" @click="copyCode">房间号 {{ room.code }} · 复制</button></div></div>
      <div class="game-meta"><span class="connection" :class="{ online: connected }">{{ connected ? '实时在线' : '正在重连' }}</span><span>第 {{ room.game.roundNumber || '—' }} 轮</span><button class="ghost" @click="emit('leave')">返回大厅</button></div>
    </header>

    <div class="game-layout flight-layout">
      <section class="table-area">
        <div v-if="room.recovered && !recoveryDismissed" class="notice-banner">服务器已恢复这局飞行棋，棋盘从保存点继续。<button @click="recoveryDismissed = true">知道了</button></div>
        <div v-if="notice" class="notice-banner commitment">{{ notice }}</div>
        <div class="flight-board panel">
          <svg viewBox="0 0 100 100" role="group" aria-label="飞行棋棋盘">
            <circle cx="50" cy="50" r="46" class="flight-board-bg" />
            <circle v-for="(point, index) in track" :key="`track-${index}`" :cx="point.x" :cy="point.y" r="2.35" class="track-cell" :style="{ fill: colors[index % 4] }" />
            <g v-for="seat in 4" :key="`home-${seat - 1}`">
              <circle v-for="step in 6" :key="step" :cx="homePoint(seat - 1, step - 1).x" :cy="homePoint(seat - 1, step - 1).y" r="2.5" class="home-cell" :style="{ fill: colors[seat - 1] }" />
            </g>
            <circle cx="50" cy="50" r="7" class="flight-center" />
            <text x="50" y="52" text-anchor="middle" class="flight-center-text">HOME</text>
            <g v-for="item in room.game.boardItems" :key="`item-${item.id}`" class="board-item" :transform="`translate(${track[item.cellIndex].x} ${track[item.cellIndex].y})`">
              <title>神秘道具，还可停留 {{ Math.max(0, item.expiresAtRound - room.game.roundNumber) }} 轮</title><circle r="3.3" /><text y="1.4" text-anchor="middle">?</text>
            </g>
            <template v-for="player in room.game.players" :key="player.id">
              <g v-for="(progress, pieceIndex) in player.pieces" :key="`${player.id}-${pieceIndex}`" class="flight-piece" :class="{ movable: isMovable(player, pieceIndex) }" :transform="`translate(${piecePoint(player, pieceIndex, progress).x} ${piecePoint(player, pieceIndex, progress).y})`" :role="isMovable(player, pieceIndex) ? 'button' : undefined" :tabindex="isMovable(player, pieceIndex) ? 0 : undefined" :aria-label="isMovable(player, pieceIndex) ? `${player.name}的${pieceIndex + 1}号飞机，移动${room.game.lastRoll}格` : undefined" @click="isMovable(player, pieceIndex) && act('MOVE', pieceIndex)" @keydown.enter.prevent="isMovable(player, pieceIndex) && act('MOVE', pieceIndex)" @keydown.space.prevent="isMovable(player, pieceIndex) && act('MOVE', pieceIndex)">
                <circle r="2.5" :style="{ fill: colors[player.seat] }" /><text y="1" text-anchor="middle">{{ pieceIndex + 1 }}</text>
              </g>
            </template>
          </svg>
          <div class="flight-player-strip">
            <div v-for="seat in 4" :key="seat - 1" class="flight-player" :class="{ current: room.game.currentSeat === seat - 1, empty: !playerAt(seat - 1) }">
              <span class="color-dot" :style="{ background: colors[seat - 1] }"></span><strong>{{ playerAt(seat - 1)?.name || colorNames[seat - 1] }}</strong><small v-if="playerAt(seat - 1)">{{ playerAt(seat - 1)?.pieces.filter(piece => piece === 57).length }}/4 到达</small><small v-else>空位</small>
            </div>
          </div>
        </div>

        <div class="action-dock flight-actions panel">
          <template v-if="!seated && ['WAITING', 'COMPLETE'].includes(room.game.phase)">
            <span class="action-hint">选择一个颜色加入游戏</span>
            <button v-for="seat in 4" :key="seat - 1" class="color-seat-button" :style="{ '--seat-color': colors[seat - 1] }" :disabled="!!playerAt(seat - 1)" @click="sit(seat - 1)">{{ colorNames[seat - 1] }}</button>
          </template>
          <template v-else-if="myTurn && room.game.phase === 'ROLL'">
            <div class="turn-status"><span>轮到你掷骰子</span><strong>不限时</strong></div>
            <div class="dice" :class="{ rolling: actionPending }">{{ diceFaces[room.game.lastRoll || 0] || '✦' }}</div>
            <button class="primary roll-button" :disabled="actionPending" @click="act('ROLL')">{{ actionPending ? '掷骰中…' : '掷骰子' }}</button>
          </template>
          <template v-else-if="myTurn && room.game.phase === 'MOVE'">
            <div class="dice">{{ diceFaces[room.game.lastRoll || 0] }}</div><div class="action-hint"><strong>掷出了 {{ room.game.lastRoll }}</strong><br />点击棋盘上发光的飞机</div>
          </template>
          <template v-else>
            <div class="action-hint"><span v-if="room.game.phase === 'WAITING'">等待玩家选择颜色</span><span v-else-if="room.game.phase === 'COMPLETE'">{{ room.game.winnerId === user.username ? '你赢得了这一局！' : '本局已经结束' }}</span><span v-else>等待 {{ playerAt(room.game.currentSeat ?? -1)?.name }} 操作 · 不限时</span></div>
            <button v-if="canStart" class="primary" @click="start">{{ room.game.gameNumber ? '开始下一局' : '开始游戏' }}</button>
            <button v-if="seated && ['WAITING', 'COMPLETE'].includes(room.game.phase)" class="ghost" @click="stand">离开颜色</button>
          </template>
        </div>
      </section>

      <aside class="hand-log panel">
        <div class="section-heading"><div><p class="eyebrow">GAME LOG</p><h2>飞行记录</h2></div><span class="room-code">#{{ room.code }}</span></div>
        <div class="log-list"><p v-for="(message, index) in [...room.game.messages].reverse()" :key="index"><span>{{ room.game.messages.length - index }}</span>{{ message }}</p><div v-if="!room.game.messages.length" class="log-empty">玩家选择颜色后，操作记录会显示在这里。</div></div>
        <div v-if="chats.length" class="chat-list"><p v-for="chat in chats.slice(-5)" :key="chat.sentAt"><strong>{{ chat.displayName }}</strong>{{ chat.text }}</p></div>
        <form class="chat-box" @submit.prevent="sendChat"><input v-model="chatText" maxlength="200" :disabled="room.status === 'CLOSED'" placeholder="和朋友说句话…" /><button class="secondary" :disabled="room.status === 'CLOSED'" aria-label="发送">↑</button></form>
        <div class="table-rules"><span>2～4 人</span><span>5 或 6 起飞</span><span>连续 6 不限次数</span><span>无操作时限</span></div>
        <p class="item-rule">第 2 轮起每两轮刷新两个神秘道具，存在三轮，功能将在后续开放。</p>
      </aside>
    </div>
    <div v-if="!connected && room.status === 'ACTIVE'" class="connection-overlay"><div class="panel"><span class="status-spinner"></span><h2>正在重新连接棋盘</h2><p>棋子、骰子和道具都已保存在服务器。</p></div></div>
    <div v-if="room.status === 'CLOSED'" class="connection-overlay closed-overlay"><div class="panel"><h2>房间已由管理员关闭</h2><p>这局飞行棋不再接受操作。</p><button class="primary" @click="emit('leave')">返回</button></div></div>
    <div v-if="error" class="toast">{{ error }}</div>
  </main>
</template>
