<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Client } from '@stomp/stompjs'
import { api } from '../api'
import { shouldApplyRoomUpdate } from '../room-updates'
import { botControlState } from '../bot-controls'
import type { ActionType, PlayerSnapshot, PokerRoomState, RoomState, UserView } from '../types'
import PlayingCard from './PlayingCard.vue'
import BotControls from './BotControls.vue'

const props = defineProps<{ initialRoom: PokerRoomState; user: UserView }>()
const emit = defineEmits<{ leave: [] }>()
const room = ref(props.initialRoom)
const error = ref('')
const connected = ref(false)
const actionPending = ref(false)
const requestPending = ref(false)
const botPending = ref(false)
const pendingVersion = ref<number | null>(null)
const recoveryDismissed = ref(false)
const commitmentNotice = ref('')
const wager = ref(props.initialRoom.config.bigBlind)
const now = ref(Date.now())
const chatText = ref('')
const chats = ref<Array<{ username: string; displayName: string; text: string; sentAt: string }>>([])
let ticker = 0
let pendingTimer = 0
let client: Client | null = null
let checkingSession = false
let disposed = false
let sessionTimer = 0
let errorTimer = 0

const me = computed(() => room.value.table.players.find(player => player.id === props.user.username))
const seated = computed(() => !!me.value)
const myTurn = computed(() => me.value?.seat === room.value.table.actorSeat)
const busy = computed(() => actionPending.value || requestPending.value || botPending.value)
const canOperate = computed(() => connected.value && room.value.status === 'ACTIVE' && !busy.value)
const betweenHands = computed(() => ['WAITING', 'COMPLETE'].includes(room.value.table.street))
const secondsLeft = computed(() => room.value.actionDeadline ? Math.max(0, Math.ceil((new Date(room.value.actionDeadline).getTime() - now.value) / 1000)) : null)
const callAmount = computed(() => Math.max(0, room.value.table.currentBet - (me.value?.committedStreet ?? 0)))
const minimumTarget = computed(() => room.value.table.currentBet === 0 ? room.value.table.minimumRaise : room.value.table.currentBet + room.value.table.minimumRaise)
const maximumTarget = computed(() => (me.value?.committedStreet ?? 0) + (me.value?.stack ?? 0))
const roomControls = computed(() => botControlState(room.value, props.user.username, connected.value, busy.value))
const canStart = computed(() => roomControls.value.canStart)
const stageName: Record<string, string> = { WAITING: '等待开局', SHUFFLE_PENDING: '洗牌承诺', PREFLOP: '翻牌前', FLOP: '翻牌圈', TURN: '转牌圈', RIVER: '河牌圈', COMPLETE: '本手结束' }

function seatStyle(seat: number) {
  const angle = (90 + seat * 360 / room.value.config.maxPlayers) * Math.PI / 180
  return { left: `${50 + 43 * Math.cos(angle)}%`, top: `${50 + 40 * Math.sin(angle)}%` }
}
function playerAt(seat: number) { return room.value.table.players.find(player => player.seat === seat) }
function isBot(id?: string) { return !!id && (room.value.bots ?? []).some(bot => bot.id === id) }
function canSit(seat: number) { return canOperate.value && !seated.value && betweenHands.value && !playerAt(seat) }
function applyBotUpdate(incoming: RoomState) {
  if (incoming.gameType === 'POKER' && incoming.id === room.value.id) applyIncoming(incoming)
}
function cardsFor(player?: PlayerSnapshot) { return player?.cards ?? [] }

async function request(path: string, method: 'POST' | 'DELETE') {
  if (!canOperate.value) return
  requestPending.value = true
  try { await api<void>(path, { method }); await refreshState() }
  catch (cause) { showError(cause) }
  finally { requestPending.value = false }
}
async function sit(seat: number) {
  if (canSit(seat)) await request(`/api/rooms/${room.value.id}/seats/${seat}`, 'POST')
}
async function stand() {
  if (betweenHands.value && seated.value) await request(`/api/rooms/${room.value.id}/seat`, 'DELETE')
}
async function start() {
  if (canStart.value) await request(`/api/rooms/${room.value.id}/start`, 'POST')
}
function act(action: ActionType) {
  if (!canOperate.value || !client?.connected || !myTurn.value || !room.value.table.legalActions.includes(action)) return
  const amount = action === 'BET' || action === 'RAISE' ? wager.value : 0
  actionPending.value = true
  pendingVersion.value = room.value.table.version
  window.clearTimeout(pendingTimer)
  pendingTimer = window.setTimeout(refreshState, 6000)
  client.publish({ destination: `/app/rooms/${room.value.id}/action`, body: JSON.stringify({ actionId: crypto.randomUUID(), action, amount, version: room.value.table.version }) })
}
function sendChat() {
  const text = chatText.value.trim()
  if (!text || !connected.value || !client?.connected || room.value.status !== 'ACTIVE') return
  client.publish({ destination: `/app/rooms/${room.value.id}/chat`, body: JSON.stringify({ text }) })
  chatText.value = ''
}
function showError(cause: unknown) {
  error.value = cause instanceof Error ? cause.message : '操作失败'
  actionPending.value = false
  pendingVersion.value = null
  window.clearTimeout(pendingTimer)
  if (error.value.includes('状态已变化')) refreshState()
  window.clearTimeout(errorTimer)
  errorTimer = window.setTimeout(() => error.value = '', 4000)
}
async function refreshState() {
  try {
    applyIncoming(await api<PokerRoomState>(`/api/rooms/${room.value.id}`))
    actionPending.value = false
    pendingVersion.value = null
    window.clearTimeout(pendingTimer)
    return true
  }
  catch (cause) { showError(cause); return false }
}
async function copyCode() {
  try { await navigator.clipboard.writeText(room.value.code); commitmentNotice.value = '邀请码已复制' }
  catch { commitmentNotice.value = `邀请码：${room.value.code}` }
  window.setTimeout(() => commitmentNotice.value = '', 2500)
}
function setWager(value: number) {
  const integer = Math.floor(value)
  wager.value = Math.max(Math.min(minimumTarget.value, maximumTarget.value), Math.min(maximumTarget.value, integer))
}
function applyIncoming(incoming: PokerRoomState) {
  if (!shouldApplyRoomUpdate(room.value.status, room.value.stateVersion ?? room.value.table.version, incoming.status, incoming.stateVersion ?? incoming.table.version)) return
  const tableChanged = incoming.table.version > room.value.table.version
  room.value = incoming
  if (incoming.status === 'CLOSED' || (pendingVersion.value !== null && incoming.table.version > pendingVersion.value)) {
    actionPending.value = false
    pendingVersion.value = null
    window.clearTimeout(pendingTimer)
  }
  wager.value = Math.max(Math.min(minimumTarget.value, maximumTarget.value), Math.min(maximumTarget.value, tableChanged ? room.value.config.bigBlind : wager.value))
}
async function checkSession() {
  if (checkingSession) return
  checkingSession = true
  sessionTimer = window.setTimeout(async () => {
    try { await api<UserView>('/api/auth/me') } catch { /* api emits session-expired for 401 */ }
    finally { checkingSession = false }
  }, 700)
}
function connect() {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  client = new Client({
    brokerURL: `${scheme}://${location.host}/ws`,
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: async () => {
      client?.subscribe(`/user/queue/rooms/${room.value.id}`, message => {
        applyIncoming(JSON.parse(message.body))
      })
      client?.subscribe(`/user/queue/rooms/${room.value.id}/shuffle`, message => {
        const event = JSON.parse(message.body)
        commitmentNotice.value = `第 ${event.handNumber} 手洗牌承诺已公布`
        window.setTimeout(() => commitmentNotice.value = '', 2500)
      })
      client?.subscribe('/user/queue/errors', message => showError(new Error(JSON.parse(message.body).message)))
      client?.subscribe(`/user/queue/rooms/${room.value.id}/chat`, message => {
        chats.value.push(JSON.parse(message.body))
        if (chats.value.length > 50) chats.value.shift()
      })
      const refreshed = await refreshState()
      if (!disposed && client?.connected) connected.value = refreshed
      if (!refreshed && !disposed) client?.forceDisconnect()
    },
    onStompError: () => { connected.value = false; checkSession() },
    onWebSocketClose: () => { connected.value = false; actionPending.value = false; checkSession() },
  })
  client.activate()
}
onMounted(() => { connect(); ticker = window.setInterval(() => now.value = Date.now(), 500) })
onBeforeUnmount(() => {
  disposed = true
  window.clearInterval(ticker)
  window.clearTimeout(pendingTimer)
  window.clearTimeout(sessionTimer)
  window.clearTimeout(errorTimer)
  client?.deactivate()
})
</script>

<template>
  <main class="game-shell">
    <header class="game-topbar">
      <div class="wordmark"><span class="brand-chip small">H</span><div><strong>{{ room.name }}</strong><button class="copy-code" @click="copyCode">桌号 {{ room.code }} · 复制</button></div></div>
      <div class="game-meta"><span class="connection" :class="{ online: connected }">{{ connected ? '实时在线' : '正在重连' }}</span><span>盲注 {{ room.config.smallBlind }} / {{ room.config.bigBlind }}</span><button class="ghost" @click="emit('leave')">返回大厅</button></div>
    </header>

    <div class="game-layout">
      <section class="table-area">
        <div v-if="room.recovered && !recoveryDismissed" class="notice-banner">服务器已恢复这张牌桌，牌局从保存点继续。<button @click="recoveryDismissed = true">知道了</button></div>
        <div v-if="commitmentNotice" class="notice-banner commitment">{{ commitmentNotice }}</div>
        <div class="poker-table">
          <div class="felt-ring"></div>
          <div class="table-center">
            <div class="hand-label">第 {{ room.table.handNumber || '—' }} 手 · {{ stageName[room.table.street] }}</div>
            <div class="pot-label"><small>底池</small><strong>{{ room.table.pot }}</strong></div>
            <div class="board-cards">
              <PlayingCard v-for="(card, index) in room.table.board" :key="index" :card="card" />
              <div v-for="index in 5 - room.table.board.length" :key="`slot-${index}`" class="card-slot"></div>
            </div>
            <p v-if="room.table.currentBet" class="current-bet">本轮最高 {{ room.table.currentBet }}</p>
          </div>

          <div v-for="seat in room.config.maxPlayers" :key="seat - 1" class="seat-position" :style="seatStyle(seat - 1)">
            <template v-if="playerAt(seat - 1)">
              <div class="player-seat" :class="{ active: room.table.actorSeat === seat - 1, folded: playerAt(seat - 1)?.status === 'FOLDED', self: playerAt(seat - 1)?.id === user.username }">
                <div class="seat-badges"><span v-if="isBot(playerAt(seat - 1)?.id)" class="poker-bot-mark" aria-label="机器人">BOT</span><span v-if="playerAt(seat - 1)?.dealer">D</span><span v-if="playerAt(seat - 1)?.smallBlind">SB</span><span v-if="playerAt(seat - 1)?.bigBlind">BB</span></div>
                <div class="hole-cards">
                  <template v-if="cardsFor(playerAt(seat - 1)).length">
                    <PlayingCard v-for="(card, cardIndex) in cardsFor(playerAt(seat - 1))" :key="cardIndex" :card="card" compact />
                  </template>
                  <template v-else-if="!['WAITING', 'SHUFFLE_PENDING'].includes(room.table.street) && playerAt(seat - 1)?.status !== 'SITTING_OUT'">
                    <PlayingCard hidden compact /><PlayingCard hidden compact />
                  </template>
                </div>
                <strong>{{ playerAt(seat - 1)?.name }}</strong>
                <span class="stack">● {{ playerAt(seat - 1)?.stack }}</span>
                <span v-if="playerAt(seat - 1)?.committedStreet" class="player-bet">{{ playerAt(seat - 1)?.committedStreet }}</span>
                <span v-if="playerAt(seat - 1)?.status === 'ALL_IN'" class="all-in">ALL IN</span>
              </div>
            </template>
            <button v-else class="empty-seat" :disabled="!canSit(seat - 1)" @click="sit(seat - 1)">＋<small>入座</small></button>
          </div>
        </div>

        <div class="action-dock panel">
          <div v-if="!seated" class="action-hint">选择一个空座位加入牌桌</div>
          <template v-else-if="myTurn">
            <div class="turn-status"><span>轮到你了</span><strong v-if="secondsLeft !== null">{{ secondsLeft }}s</strong></div>
            <div class="action-buttons">
            <button v-if="room.table.legalActions.includes('FOLD')" class="danger" :disabled="!canOperate" @click="act('FOLD')">弃牌</button>
              <button v-if="room.table.legalActions.includes('CHECK')" class="secondary" :disabled="!canOperate" @click="act('CHECK')">过牌</button>
              <button v-if="room.table.legalActions.includes('CALL')" class="secondary" :disabled="!canOperate" @click="act('CALL')">跟注 {{ Math.min(callAmount, me?.stack ?? 0) }}</button>
              <div v-if="room.table.legalActions.includes('BET') || room.table.legalActions.includes('RAISE')" class="wager-control">
                <div class="wager-presets"><button type="button" @click="setWager(minimumTarget)">最小</button><button type="button" @click="setWager(Math.floor(room.table.pot / 2))">半池</button><button type="button" @click="setWager(room.table.pot)">一池</button></div>
                <input v-model.number="wager" type="range" :min="Math.min(minimumTarget, maximumTarget)" :max="maximumTarget" :step="room.config.smallBlind" />
                <input v-model.number="wager" type="number" :min="Math.min(minimumTarget, maximumTarget)" :max="maximumTarget" />
                <button class="primary" :disabled="!canOperate" @click="act(room.table.legalActions.includes('BET') ? 'BET' : 'RAISE')">{{ actionPending ? '提交中…' : room.table.legalActions.includes('BET') ? '下注' : '加注' }}</button>
              </div>
              <button v-if="room.table.legalActions.includes('ALL_IN')" class="allin-button" :disabled="!canOperate" @click="act('ALL_IN')">All-in</button>
            </div>
          </template>
          <template v-else>
            <div class="action-hint">
              <span v-if="roomControls.selfOutOfChips">你的筹码已用完，请先离座，再重新入座继续。</span>
              <span v-else-if="room.table.street === 'WAITING'">等待房主开始第一手牌</span>
              <span v-else-if="room.table.street === 'SHUFFLE_PENDING'">承诺已保存，正在发牌…</span>
              <span v-else-if="room.table.street === 'COMPLETE'">本手结束，准备下一手</span>
              <span v-else>等待其他玩家行动 <b v-if="secondsLeft !== null">· {{ secondsLeft }}s</b></span>
            </div>
            <button v-if="canStart" class="primary" :disabled="!canOperate" @click="start">{{ requestPending ? '正在开始…' : room.table.handNumber ? '开始下一手' : '开始第一手' }}</button>
            <button v-if="betweenHands && seated" class="ghost" :disabled="!canOperate" @click="stand">离座</button>
          </template>
        </div>
        <BotControls :room="room" :username="user.username" :connected="connected" :busy="busy" @pending="botPending = $event" @updated="applyBotUpdate" @error="showError" />
      </section>

      <aside class="hand-log panel">
        <div class="section-heading"><div><p class="eyebrow">HAND LOG</p><h2>牌局记录</h2></div><span class="room-code">#{{ room.code }}</span></div>
        <div class="log-list">
          <p v-for="(message, index) in [...room.table.messages].reverse()" :key="index"><span>{{ room.table.messages.length - index }}</span>{{ message }}</p>
          <div v-if="!room.table.messages.length" class="log-empty">玩家入座后，操作记录会显示在这里。</div>
        </div>
        <div v-if="chats.length" class="chat-list">
          <p v-for="chat in chats.slice(-5)" :key="chat.sentAt"><strong>{{ chat.displayName }}</strong>{{ chat.text }}</p>
        </div>
        <form class="chat-box" @submit.prevent="sendChat"><input v-model="chatText" maxlength="200" :disabled="!connected || room.status === 'CLOSED'" placeholder="和桌上的朋友说句话…" /><button class="secondary" :disabled="!connected || room.status === 'CLOSED' || !chatText.trim()" aria-label="发送">↑</button></form>
        <div class="table-rules"><span>初始筹码 {{ room.config.initialStack }}</span><span>{{ room.config.maxPlayers }} 人桌</span><span>{{ room.config.actionSeconds }} 秒行动</span></div>
        <details v-if="room.table.shuffleCommitment" class="fairness-proof">
          <summary>本手洗牌校验</summary>
          <small>承诺</small><code>{{ room.table.shuffleCommitment }}</code>
          <template v-if="room.table.shuffleSeedReveal"><small>公开种子</small><code>{{ room.table.shuffleSeedReveal }}</code></template>
        </details>
      </aside>
    </div>
    <div v-if="!connected && room.status === 'ACTIVE'" class="connection-overlay"><div class="panel"><span class="status-spinner"></span><h2>正在重新连接牌桌</h2><p>你的筹码和牌局已经保存在服务器，请不要重复操作。</p></div></div>
    <div v-if="room.status === 'CLOSED'" class="connection-overlay closed-overlay"><div class="panel"><h2>房间已由管理员关闭</h2><p>这张牌桌不再接受操作，你可以返回“我的牌局”。</p><button class="primary" @click="emit('leave')">返回</button></div></div>
    <div v-if="error" class="toast">{{ error }}</div>
  </main>
</template>

<style scoped>
.seat-badges .poker-bot-mark { width: auto; min-width: 29px; border-radius: 6px; color: #dcebe2; background: #34644e; }
</style>
