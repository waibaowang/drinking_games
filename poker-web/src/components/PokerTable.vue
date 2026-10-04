<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Client } from '@stomp/stompjs'
import { api } from '../api'
import type { ActionType, PlayerSnapshot, RoomState, UserView } from '../types'
import PlayingCard from './PlayingCard.vue'

const props = defineProps<{ initialRoom: RoomState; user: UserView }>()
const emit = defineEmits<{ leave: [] }>()
const room = ref(props.initialRoom)
const error = ref('')
const connected = ref(false)
const wager = ref(props.initialRoom.config.bigBlind)
const now = ref(Date.now())
const chatText = ref('')
const chats = ref<Array<{ username: string; displayName: string; text: string; sentAt: string }>>([])
let ticker = 0
let client: Client | null = null

const me = computed(() => room.value.table.players.find(player => player.id === props.user.username))
const seated = computed(() => !!me.value)
const myTurn = computed(() => me.value?.seat === room.value.table.actorSeat)
const secondsLeft = computed(() => room.value.actionDeadline ? Math.max(0, Math.ceil((new Date(room.value.actionDeadline).getTime() - now.value) / 1000)) : null)
const callAmount = computed(() => Math.max(0, room.value.table.currentBet - (me.value?.committedStreet ?? 0)))
const minimumTarget = computed(() => room.value.table.currentBet === 0 ? room.value.table.minimumRaise : room.value.table.currentBet + room.value.table.minimumRaise)
const maximumTarget = computed(() => (me.value?.committedStreet ?? 0) + (me.value?.stack ?? 0))
const canStart = computed(() => props.user.username === room.value.hostUsername && ['WAITING', 'COMPLETE'].includes(room.value.table.street))
const stageName: Record<string, string> = { WAITING: '等待开局', PREFLOP: '翻牌前', FLOP: '翻牌圈', TURN: '转牌圈', RIVER: '河牌圈', COMPLETE: '本手结束' }

function seatStyle(seat: number) {
  const angle = (90 + seat * 360 / room.value.config.maxPlayers) * Math.PI / 180
  return { left: `${50 + 43 * Math.cos(angle)}%`, top: `${50 + 40 * Math.sin(angle)}%` }
}
function playerAt(seat: number) { return room.value.table.players.find(player => player.seat === seat) }
function cardsFor(player?: PlayerSnapshot) { return player?.cards ?? [] }

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
function act(action: ActionType) {
  if (!client?.connected) { error.value = '实时连接尚未恢复'; return }
  const amount = action === 'BET' || action === 'RAISE' ? wager.value : 0
  client.publish({ destination: `/app/rooms/${room.value.id}/action`, body: JSON.stringify({ actionId: crypto.randomUUID(), action, amount, version: room.value.table.version }) })
}
function sendChat() {
  const text = chatText.value.trim()
  if (!text || !client?.connected) return
  client.publish({ destination: `/app/rooms/${room.value.id}/chat`, body: JSON.stringify({ text }) })
  chatText.value = ''
}
function showError(cause: unknown) {
  error.value = cause instanceof Error ? cause.message : '操作失败'
  window.setTimeout(() => error.value = '', 4000)
}
function connect() {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  client = new Client({
    brokerURL: `${scheme}://${location.host}/ws`,
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      connected.value = true
      client?.subscribe(`/user/queue/rooms/${room.value.id}`, message => {
        room.value = JSON.parse(message.body)
        wager.value = Math.min(maximumTarget.value, Math.max(minimumTarget.value, room.value.config.bigBlind))
      })
      client?.subscribe('/user/queue/errors', message => showError(new Error(JSON.parse(message.body).message)))
      client?.subscribe(`/user/queue/rooms/${room.value.id}/chat`, message => {
        chats.value.push(JSON.parse(message.body))
        if (chats.value.length > 50) chats.value.shift()
      })
    },
    onWebSocketClose: () => connected.value = false,
  })
  client.activate()
}
onMounted(() => { connect(); ticker = window.setInterval(() => now.value = Date.now(), 500) })
onBeforeUnmount(() => { window.clearInterval(ticker); client?.deactivate() })
</script>

<template>
  <main class="game-shell">
    <header class="game-topbar">
      <div class="wordmark"><span class="brand-chip small">H</span><div><strong>{{ room.name }}</strong><small>桌号 {{ room.code }}</small></div></div>
      <div class="game-meta"><span class="connection" :class="{ online: connected }">{{ connected ? '实时在线' : '正在重连' }}</span><span>盲注 {{ room.config.smallBlind }} / {{ room.config.bigBlind }}</span><button class="ghost" @click="emit('leave')">返回大厅</button></div>
    </header>

    <div class="game-layout">
      <section class="table-area">
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
                <div class="seat-badges"><span v-if="playerAt(seat - 1)?.dealer">D</span><span v-if="playerAt(seat - 1)?.smallBlind">SB</span><span v-if="playerAt(seat - 1)?.bigBlind">BB</span></div>
                <div class="hole-cards">
                  <template v-if="cardsFor(playerAt(seat - 1)).length">
                    <PlayingCard v-for="(card, cardIndex) in cardsFor(playerAt(seat - 1))" :key="cardIndex" :card="card" compact />
                  </template>
                  <template v-else-if="room.table.street !== 'WAITING' && playerAt(seat - 1)?.status !== 'SITTING_OUT'">
                    <PlayingCard hidden compact /><PlayingCard hidden compact />
                  </template>
                </div>
                <strong>{{ playerAt(seat - 1)?.name }}</strong>
                <span class="stack">● {{ playerAt(seat - 1)?.stack }}</span>
                <span v-if="playerAt(seat - 1)?.committedStreet" class="player-bet">{{ playerAt(seat - 1)?.committedStreet }}</span>
                <span v-if="playerAt(seat - 1)?.status === 'ALL_IN'" class="all-in">ALL IN</span>
              </div>
            </template>
            <button v-else class="empty-seat" :disabled="seated || !['WAITING', 'COMPLETE'].includes(room.table.street)" @click="sit(seat - 1)">＋<small>入座</small></button>
          </div>
        </div>

        <div class="action-dock panel">
          <div v-if="!seated" class="action-hint">选择一个空座位加入牌桌</div>
          <template v-else-if="myTurn">
            <div class="turn-status"><span>轮到你了</span><strong v-if="secondsLeft !== null">{{ secondsLeft }}s</strong></div>
            <div class="action-buttons">
              <button v-if="room.table.legalActions.includes('FOLD')" class="danger" @click="act('FOLD')">弃牌</button>
              <button v-if="room.table.legalActions.includes('CHECK')" class="secondary" @click="act('CHECK')">过牌</button>
              <button v-if="room.table.legalActions.includes('CALL')" class="secondary" @click="act('CALL')">跟注 {{ Math.min(callAmount, me?.stack ?? 0) }}</button>
              <div v-if="room.table.legalActions.includes('BET') || room.table.legalActions.includes('RAISE')" class="wager-control">
                <input v-model.number="wager" type="range" :min="Math.min(minimumTarget, maximumTarget)" :max="maximumTarget" :step="room.config.smallBlind" />
                <input v-model.number="wager" type="number" :min="Math.min(minimumTarget, maximumTarget)" :max="maximumTarget" />
                <button class="primary" @click="act(room.table.legalActions.includes('BET') ? 'BET' : 'RAISE')">{{ room.table.legalActions.includes('BET') ? '下注' : '加注' }}</button>
              </div>
              <button v-if="room.table.legalActions.includes('ALL_IN')" class="allin-button" @click="act('ALL_IN')">All-in</button>
            </div>
          </template>
          <template v-else>
            <div class="action-hint">
              <span v-if="room.table.street === 'WAITING'">等待房主开始第一手牌</span>
              <span v-else-if="room.table.street === 'COMPLETE'">本手结束，准备下一手</span>
              <span v-else>等待其他玩家行动 <b v-if="secondsLeft !== null">· {{ secondsLeft }}s</b></span>
            </div>
            <button v-if="canStart" class="primary" @click="start">{{ room.table.handNumber ? '开始下一手' : '开始第一手' }}</button>
            <button v-if="room.table.street === 'WAITING' || room.table.street === 'COMPLETE'" class="ghost" @click="stand">离座</button>
          </template>
        </div>
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
        <form class="chat-box" @submit.prevent="sendChat"><input v-model="chatText" maxlength="200" placeholder="和桌上的朋友说句话…" /><button class="secondary" aria-label="发送">↑</button></form>
        <div class="table-rules"><span>初始筹码 {{ room.config.initialStack }}</span><span>{{ room.config.maxPlayers }} 人桌</span><span>{{ room.config.actionSeconds }} 秒行动</span></div>
        <details v-if="room.table.shuffleCommitment" class="fairness-proof">
          <summary>本手洗牌校验</summary>
          <small>承诺</small><code>{{ room.table.shuffleCommitment }}</code>
          <template v-if="room.table.shuffleSeedReveal"><small>公开种子</small><code>{{ room.table.shuffleSeedReveal }}</code></template>
        </details>
      </aside>
    </div>
    <div v-if="error" class="toast">{{ error }}</div>
  </main>
</template>
