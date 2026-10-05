<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId } from 'vue'
import { Client } from '@stomp/stompjs'
import { api } from '../api'
import { shouldApplyRoomUpdate } from '../room-updates'
import { botControlState, flightBotTurnMessage } from '../bot-controls'
import type { FlightPlayerSnapshot, FlightRoomState, RoomState, UserView } from '../types'
import FlightPlane from './FlightPlane.vue'
import FlightDetails from './FlightDetails.vue'
import BotControls from './BotControls.vue'
import { CELL_SIZE, colors, colorNames, track, hangarCenters, homePoint, hangarPoint, piecePoint, globalCell } from '../flight-board'
import '../flight.css'

const props = defineProps<{ initialRoom: FlightRoomState; user: UserView }>()
const emit = defineEmits<{ leave: [] }>()
const room = ref(props.initialRoom)
const error = ref('')
const connected = ref(false)
const actionPending = ref(false)
const requestPending = ref(false)
const botPending = ref(false)
const pendingVersion = ref<number | null>(null)
const drawer = ref<'rules' | 'log' | 'chat' | null>(null)
const drawerDialog = ref<HTMLDialogElement | null>(null)
const svgId = useId()
const recoveryDismissed = ref(false)
const notice = ref('')
const chatText = ref('')
const chats = ref<Array<{ username: string; displayName: string; text: string; sentAt: string }>>([])
let pendingTimer = 0
let client: Client | null = null
let checkingSession = false
let disposed = false
let sessionTimer = 0
let messageTimer = 0

const me = computed(() => room.value.game.players.find(player => player.id === props.user.username))
const seated = computed(() => !!me.value)
const myTurn = computed(() => me.value?.seat === room.value.game.currentSeat)
const busy = computed(() => actionPending.value || requestPending.value || botPending.value)
const canOperate = computed(() => connected.value && room.value.status === 'ACTIVE' && !busy.value)
const betweenGames = computed(() => ['WAITING', 'COMPLETE'].includes(room.value.game.phase))
const canStart = computed(() => botControlState(room.value, props.user.username, connected.value, busy.value).canStart)
const botTurnMessage = computed(() => flightBotTurnMessage(room.value))
const botPaused = computed(() => !!botTurnMessage.value && room.value.botSettings?.enabled === false)
const turnTitle = computed(() => {
  if (room.value.game.phase === 'WAITING') return seated.value ? '等待房主开始游戏' : '选择颜色，一起起飞'
  if (room.value.game.phase === 'COMPLETE') return room.value.game.winnerId === props.user.username ? '你赢得了这一局！' : `${room.value.game.players.find(p => p.id === room.value.game.winnerId)?.name || '玩家'} 获得胜利`
  if (botTurnMessage.value) return botTurnMessage.value
  if (myTurn.value) return room.value.game.phase === 'MOVE' ? `掷出了 ${room.value.game.lastRoll}，选择飞机` : '轮到你掷骰子'
  return `等待 ${playerAt(room.value.game.currentSeat ?? -1)?.name || '玩家'} 操作`
})
const dicePips: Record<number, number[]> = { 1: [4], 2: [0, 8], 3: [0, 4, 8], 4: [0, 2, 6, 8], 5: [0, 2, 4, 6, 8], 6: [0, 2, 3, 5, 6, 8] }
const recentMessages = computed(() => [...room.value.game.messages].reverse())
const boardPieces = computed(() => room.value.game.players.flatMap(player => player.pieces.map((progress, index) => {
  const point = piecePoint(player.seat, index, progress)
  const cell = globalCell(player.seat, progress)
  const stacked = player.pieces.map((value, i) => ({ value, i })).filter(piece => progress >= 0 && piece.value === progress)
  const stackIndex = stacked.findIndex(piece => piece.i === index)
  const offset = stacked.length > 1 ? [[-12, -10], [12, -10], [-12, 10], [12, 10]][stackIndex] : [0, 0]
  return { player, index, progress, cell, x: point.x + offset[0], y: point.y + offset[1], scale: stacked.length > 1 ? .64 : progress < 0 ? 1 : .8 }
})))

function playerAt(seat: number) { return room.value.game.players.find(player => player.seat === seat) }
function isBot(id?: string) { return !!id && (room.value.bots ?? []).some(bot => bot.id === id) }
function applyBotUpdate(incoming: RoomState) {
  if (incoming.gameType === 'FLIGHT' && incoming.id === room.value.id) applyIncoming(incoming)
}
function isMovable(player: FlightPlayerSnapshot, piece: number) {
  return canOperate.value
    && player.id === props.user.username && myTurn.value && room.value.game.phase === 'MOVE'
    && room.value.game.legalPieceIndexes.includes(piece)
}
function canSit(seat: number) { return canOperate.value && !seated.value && betweenGames.value && !playerAt(seat) }
function tileFill(seat: number) { return `url(#${svgId}-tile-${seat})` }
function completed(seat: number) { return playerAt(seat)?.pieces.filter(piece => piece === 57).length ?? 0 }
async function openDrawer(topic: 'rules' | 'log' | 'chat') {
  if (!connected.value || room.value.status !== 'ACTIVE') return
  drawer.value = topic
  await nextTick()
  if (!drawerDialog.value?.open) drawerDialog.value?.showModal()
}
function closeDrawer() { drawerDialog.value?.close(); drawer.value = null }
function clearPending() {
  actionPending.value = false
  pendingVersion.value = null
  window.clearTimeout(pendingTimer)
}

async function request(path: string, method: 'POST' | 'DELETE') {
  if (!canOperate.value) return
  requestPending.value = true
  try { await api<void>(path, { method }); await refreshState() }
  catch (cause) { showError(cause) }
  finally { requestPending.value = false }
}
async function sit(seat: number) { if (canSit(seat)) await request(`/api/rooms/${room.value.id}/seats/${seat}`, 'POST') }
async function stand() {
  if (betweenGames.value && seated.value) await request(`/api/rooms/${room.value.id}/seat`, 'DELETE')
}
async function start() {
  if (canStart.value) await request(`/api/rooms/${room.value.id}/start`, 'POST')
}
function act(action: 'ROLL' | 'MOVE', pieceIndex?: number) {
  if (!canOperate.value || !client?.connected || !myTurn.value) return
  if (action === 'ROLL' && room.value.game.phase !== 'ROLL') return
  if (action === 'MOVE' && (pieceIndex === undefined || !me.value || !isMovable(me.value, pieceIndex))) return
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
  if (!text || !connected.value || !client?.connected || room.value.status !== 'ACTIVE') return
  client.publish({ destination: `/app/flight/rooms/${room.value.id}/chat`, body: JSON.stringify({ text }) })
  chatText.value = ''
}
function showError(cause: unknown) {
  error.value = cause instanceof Error ? cause.message : '操作失败'
  clearPending()
  if (error.value.includes('状态已变化')) refreshState(true)
  window.clearTimeout(messageTimer)
  messageTimer = window.setTimeout(() => error.value = '', 4000)
}
async function refreshState(settlePending = false) {
  try {
    applyIncoming(await api<FlightRoomState>(`/api/rooms/${room.value.id}`))
    if (settlePending) clearPending()
    return true
  }
  catch (cause) { showError(cause); return false }
}
function applyIncoming(incoming: FlightRoomState) {
  if (!shouldApplyRoomUpdate(room.value.status, room.value.stateVersion ?? room.value.game.version, incoming.status, incoming.stateVersion ?? incoming.game.version)) return
  room.value = incoming
  if (incoming.status === 'CLOSED') closeDrawer()
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
  sessionTimer = window.setTimeout(async () => {
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
      const refreshed = await refreshState(true)
      if (!disposed && client?.connected) connected.value = refreshed
      if (!refreshed && !disposed) client?.forceDisconnect()
    },
    onStompError: () => { connected.value = false; closeDrawer(); checkSession() },
    onWebSocketClose: () => { connected.value = false; closeDrawer(); checkSession() },
  })
  client.activate()
}

onMounted(connect)
onBeforeUnmount(() => {
  disposed = true
  window.clearTimeout(pendingTimer)
  window.clearTimeout(sessionTimer)
  window.clearTimeout(messageTimer)
  drawerDialog.value?.close()
  client?.deactivate()
})
</script>

<template>
  <main class="game-shell flight-shell">
    <div class="flight-content">
      <header class="flight-header" :inert="!connected || room.status === 'CLOSED'">
        <button class="flight-back" @click="emit('leave')">返回大厅</button>
        <h1 class="flight-title">飞行棋<span aria-hidden="true">✈</span></h1>
        <span class="flight-connection" :class="{ online: connected }"><i aria-hidden="true"></i>{{ connected ? '已连接' : '重连中' }}</span>
      </header>
      <div class="flight-room-bar" :inert="!connected || room.status === 'CLOSED'">
        <strong class="flight-room-name" :title="room.name">{{ room.name }}</strong>
        <span class="flight-round">第 <b>{{ room.game.roundNumber || '—' }}</b> 轮</span>
        <button class="flight-code" @click="copyCode" aria-label="复制房间邀请码">房间号 {{ room.code }} <span aria-hidden="true">▢</span></button>
      </div>

      <div class="flight-layout" :inert="!connected || room.status === 'CLOSED'">
        <section class="flight-table-area">
          <div v-if="room.recovered && !recoveryDismissed" class="flight-notice" role="status">服务器已恢复这局飞行棋，从保存点继续。<button @click="recoveryDismissed = true">知道了</button></div>
          <div v-if="notice" class="flight-notice" role="status">{{ notice }}</div>
          <div class="flight-board-wrap">
            <svg class="flight-map" viewBox="0 0 1000 1000" role="group" aria-label="四色十字飞行棋棋盘">
              <defs>
                <linearGradient v-for="(color, seat) in colors" :id="`${svgId}-tile-${seat}`" :key="seat" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" :stop-color="color" /><stop offset="1" :stop-color="color" stop-opacity=".88" />
                </linearGradient>
                <linearGradient :id="`${svgId}-shine`" x1="0" y1="0" x2="0" y2="1"><stop stop-color="#fff" stop-opacity=".46" /><stop offset=".45" stop-color="#fff" stop-opacity=".06" /><stop offset="1" stop-color="#043b65" stop-opacity=".16" /></linearGradient>
                <linearGradient :id="`${svgId}-corner`" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#f8f4e7" /><stop offset="1" stop-color="#b5c4ce" /></linearGradient>
              </defs>
              <g transform="translate(500 505) rotate(-35) scale(6)" opacity=".08" aria-hidden="true"><FlightPlane :seat="2" /></g>
              <g v-for="seat in 4" :key="`corner-${seat}`" :transform="`translate(500 500) rotate(${(seat - 1) * 90})`" aria-hidden="true">
                <path d="M142 -224 H194 Q194 -194 224 -194 V-142 H168 Q142 -142 142 -168Z" :fill="`url(#${svgId}-corner)`" stroke="#fff" stroke-opacity=".65" stroke-width="2" />
              </g>
              <g v-for="(point, index) in track" :key="`track-${index}`" :transform="`translate(${point.x} ${point.y})`" :data-cell="index" aria-hidden="true">
                <rect :x="-CELL_SIZE / 2 + 1" :y="-CELL_SIZE / 2 + 5" :width="CELL_SIZE - 2" :height="CELL_SIZE - 2" rx="5" fill="#164f77" opacity=".48" />
                <rect class="flight-tile" :x="-CELL_SIZE / 2 + 1" :y="-CELL_SIZE / 2 + 1" :width="CELL_SIZE - 2" :height="CELL_SIZE - 2" rx="5" :fill="tileFill(index % 4)" />
                <rect :x="-CELL_SIZE / 2 + 3" :y="-CELL_SIZE / 2 + 3" :width="CELL_SIZE - 6" :height="CELL_SIZE - 6" rx="4" :fill="`url(#${svgId}-shine)`" />
                <circle class="flight-tile-mark" r="17" fill="none" stroke="#fff" stroke-opacity=".8" stroke-width="1.7" stroke-dasharray="20 5" />
              </g>
              <g v-for="seat in 4" :key="`home-${seat}`" aria-hidden="true">
                <g v-for="step in 6" :key="step" :transform="`translate(${homePoint(seat - 1, step - 1).x} ${homePoint(seat - 1, step - 1).y}) rotate(${(seat - 1) * 90})`" :data-home="`${seat - 1}-${step - 1}`">
                  <rect class="flight-home-tile" x="-27" y="-27" width="54" height="54" rx="5" :fill="tileFill(seat - 1)" />
                  <rect x="-25" y="-25" width="50" height="50" rx="4" :fill="`url(#${svgId}-shine)`" />
                  <path d="M-10 -8 L0 1 L10 -8 M-10 1 L0 10 L10 1" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />
                </g>
              </g>
              <g v-for="(center, seat) in hangarCenters" :key="`airport-${seat}`" :transform="`translate(${center.x} ${center.y})`" class="flight-hangar" :class="{ selectable: canSit(seat) }" :role="canSit(seat) ? 'button' : undefined" :tabindex="canSit(seat) ? 0 : undefined" :aria-label="canSit(seat) ? `选择${colorNames[seat]}加入游戏` : `${colorNames[seat]}机场`" @click="sit(seat)" @keydown.enter.prevent="sit(seat)" @keydown.space.prevent="sit(seat)">
                <title>{{ playerAt(seat)?.name || `${colorNames[seat]}空位，选择颜色加入` }}</title>
                <g :transform="`rotate(${seat * 90})`" aria-hidden="true">
                  <path d="M-95 -25 Q-165 -34 -165 -85 Q-165 -120 -135 -120 Q-100 -120 -95 -88Z" :fill="tileFill(seat)" stroke="#ffffff88" stroke-width="5" />
                  <g transform="translate(-135 -85) rotate(-35) scale(.42)"><FlightPlane :seat="seat" silhouette /></g>
                </g>
                <rect x="-108" y="-103" width="216" height="216" rx="28" fill="#064974" opacity=".4" />
                <rect x="-108" y="-108" width="216" height="216" rx="28" :fill="tileFill(seat)" stroke="#ffffff9c" stroke-width="7" />
                <rect x="-96" y="-96" width="192" height="192" rx="21" :fill="`url(#${svgId}-shine)`" stroke="#fff" stroke-opacity=".4" stroke-width="3" />
                <g v-for="piece in 4" :key="piece" :transform="`translate(${hangarPoint(seat, piece - 1).x - center.x} ${hangarPoint(seat, piece - 1).y - center.y})`" aria-hidden="true">
                  <rect x="-36" y="-37" width="72" height="76" rx="10" fill="#fff" opacity=".2" />
                  <FlightPlane :seat="seat" silhouette />
                </g>
              </g>
              <g transform="translate(500 500)" aria-hidden="true">
                <rect x="-67" y="-63" width="134" height="134" rx="12" fill="#124d75" opacity=".5" />
                <path d="M-65 -65 H65 L0 0Z" :fill="tileFill(0)" /><path d="M65 -65 V65 L0 0Z" :fill="tileFill(1)" /><path d="M65 65 H-65 L0 0Z" :fill="tileFill(2)" /><path d="M-65 65 V-65 L0 0Z" :fill="tileFill(3)" />
                <rect x="-65" y="-65" width="130" height="130" rx="10" fill="none" stroke="#fff" stroke-width="4" stroke-opacity=".85" />
                <circle r="33" fill="#1264b6" stroke="#e4f8ff" stroke-width="3" /><text class="flight-finish-label" y="8" text-anchor="middle" fill="#fff" font-size="25" font-weight="900">终点</text>
              </g>
              <g v-for="item in room.game.boardItems" :key="`item-${item.id}`" :transform="`translate(${track[item.cellIndex].x} ${track[item.cellIndex].y})`" class="flight-item-art" :data-item-cell="item.cellIndex" role="img" :aria-label="`神秘道具，剩余 ${item.expiresAtRound - room.game.roundNumber} 轮，效果待开放`">
                <title>神秘道具，剩余 {{ item.expiresAtRound - room.game.roundNumber }} 轮，效果待开放</title>
                <rect x="-20" y="-16" width="40" height="37" rx="5" fill="#8849d2" stroke="#edcbff" stroke-width="2" /><path d="M-20 -5 H20 M0 -16 V21" stroke="#ffe277" stroke-width="6" /><path d="M0 -16 C-26 -39 -28 -10 0 -16 C26 -39 28 -10 0 -16" fill="none" stroke="#ffe277" stroke-width="4" />
                <text x="0" y="16" text-anchor="middle" fill="#fff" font-size="19" font-weight="800">?</text>
                <circle cx="20" cy="-23" r="13" fill="#9c47d5" stroke="#fff" stroke-width="2" /><text x="20" y="-18" text-anchor="middle" fill="#fff" font-size="16" font-weight="800">{{ item.expiresAtRound - room.game.roundNumber }}</text>
              </g>
              <g v-for="piece in boardPieces" :key="`${piece.player.id}-${piece.index}`" class="flight-piece-position" :transform="`translate(${piece.x} ${piece.y})`" :data-piece="`${piece.player.id}-${piece.index}`" :data-piece-cell="piece.cell">
                <g class="flight-piece-interactive" :class="{ movable: isMovable(piece.player, piece.index) }" :role="isMovable(piece.player, piece.index) ? 'button' : 'img'" :tabindex="isMovable(piece.player, piece.index) ? 0 : undefined" :aria-label="`${piece.player.name}的${piece.index + 1}号飞机${isMovable(piece.player, piece.index) ? `，移动${room.game.lastRoll}格` : piece.progress === 57 ? '，已到达终点' : ''}`" @click="isMovable(piece.player, piece.index) && act('MOVE', piece.index)" @keydown.enter.prevent="isMovable(piece.player, piece.index) && act('MOVE', piece.index)" @keydown.space.prevent="isMovable(piece.player, piece.index) && act('MOVE', piece.index)">
                  <circle v-if="isMovable(piece.player, piece.index)" r="34" fill="transparent" pointer-events="all" />
                  <g class="flight-piece-art" :transform="`scale(${piece.scale})`">
                    <circle v-if="isMovable(piece.player, piece.index)" class="flight-piece-halo" r="38" fill="#fff9bc33" stroke="#ffe78a" stroke-width="4" />
                    <FlightPlane :seat="piece.player.seat" :number="piece.index + 1" />
                  </g>
                </g>
              </g>
            </svg>
            <div v-for="(center, seat) in hangarCenters" :key="`label-${seat}`" class="flight-airport-label" :class="[`seat-${seat}`, { current: room.game.currentSeat === seat, empty: !playerAt(seat) }]" :style="{ '--seat-color': colors[seat], left: `${center.x / 10}%`, top: seat === 0 || seat === 3 ? '4%' : '96%' }" :title="playerAt(seat)?.name">
              <span class="flight-label-name"><b v-if="isBot(playerAt(seat)?.id)" class="flight-bot-mark" aria-label="机器人">BOT</b>{{ playerAt(seat)?.id === user.username ? `${playerAt(seat)?.name}（我）` : playerAt(seat)?.name || colorNames[seat] }}</span><small>{{ playerAt(seat) ? `${completed(seat)}/4` : '空位' }}</small>
            </div>
          </div>

          <div class="flight-action-panel" aria-live="polite">
            <div class="flight-turn-status"><strong>{{ turnTitle }}</strong><small>{{ botPaused ? '等待继续机器人' : betweenGames ? '2～4 人 · 5 或 6 起飞' : '不限时' }}</small></div>
              <div v-if="!betweenGames" class="flight-die" :class="{ rolling: actionPending }" role="img" :aria-label="room.game.lastRoll ? `${room.game.phase === 'MOVE' ? '本次' : '上次'}骰子 ${room.game.lastRoll} 点` : '等待掷骰'">
                <span v-for="pip in dicePips[room.game.lastRoll ?? 0] || []" :key="pip" class="pip" :class="`pip-${pip + 1}`"></span><span v-if="!room.game.lastRoll" class="flight-die-ready" aria-hidden="true">✦</span>
                <small v-if="room.game.lastRoll" class="flight-die-caption" aria-hidden="true">{{ room.game.phase === 'MOVE' ? '本次' : '上次' }}</small>
              </div>
            <template v-if="myTurn && !betweenGames">
              <button v-if="room.game.phase === 'ROLL'" class="flight-primary flight-roll" :disabled="!canOperate" @click="act('ROLL')">{{ actionPending ? '掷骰中…' : '掷骰子' }}</button>
              <div v-else class="flight-move-options"><button v-for="index in room.game.legalPieceIndexes" :key="index" class="flight-piece-choice" :style="{ '--seat-color': colors[me?.seat ?? 0] }" :disabled="!canOperate" @click="act('MOVE', index)">{{ index + 1 }} 号飞机</button><small>{{ actionPending ? '移动中…' : '也可点击发光的飞机' }}</small></div>
            </template>
            <div v-else-if="!seated && betweenGames" class="flight-seat-options"><button v-for="seat in 4" :key="seat" class="flight-seat" :style="{ '--seat-color': colors[seat - 1] }" :disabled="!canSit(seat - 1)" @click="sit(seat - 1)">{{ colorNames[seat - 1] }}</button></div>
            <template v-else><button v-if="canStart" class="flight-primary" :disabled="!canOperate" @click="start">{{ requestPending ? '正在开始…' : room.game.gameNumber ? '开始下一局' : '开始游戏' }}</button><button v-if="seated && betweenGames" class="flight-secondary" :disabled="!canOperate" @click="stand">离开座位</button></template>
          </div>
          <BotControls :room="room" :username="user.username" :connected="connected" :busy="busy" @pending="botPending = $event" @updated="applyBotUpdate" @error="showError" />
          <nav class="flight-tools" aria-label="飞行棋工具">
            <button @click="openDrawer('rules')"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><rect x="4" y="3" width="16" height="18" rx="3"/><path d="M8 8h8M8 12h8M8 16h5"/></svg>规则</button><button @click="openDrawer('log')"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>飞行记录</button><button @click="openDrawer('chat')"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M20 15a3 3 0 0 1-3 3H9l-5 3V6a3 3 0 0 1 3-3h10a3 3 0 0 1 3 3Z"/><path d="M8 8h8M8 12h5"/></svg>聊天</button>
          </nav>
          <p class="flight-item-note">神秘道具效果待开放</p>
        </section>
        <aside class="flight-sidebar" aria-label="飞行记录和聊天"><FlightDetails :room="room" :messages="recentMessages" :chats="chats" :connected="connected" v-model:chat-text="chatText" @send-chat="sendChat" /></aside>
      </div>
      <dialog ref="drawerDialog" class="flight-drawer-backdrop" aria-labelledby="flight-drawer-title" @close="drawer = null" @click="($event.target === drawerDialog) && closeDrawer()">
        <section class="flight-drawer">
          <header class="flight-drawer-header"><h2 id="flight-drawer-title">{{ drawer === 'rules' ? '游戏规则' : drawer === 'chat' ? '和朋友聊聊' : '飞行记录' }}</h2><button class="flight-close" aria-label="关闭面板" @click="closeDrawer">×</button></header>
          <FlightDetails v-if="drawer" :room="room" :messages="recentMessages" :chats="chats" :connected="connected" :topic="drawer" v-model:chat-text="chatText" @send-chat="sendChat" />
        </section>
      </dialog>
      <div v-if="!connected && room.status === 'ACTIVE'" class="flight-overlay"><div class="flight-modal" role="status"><span class="flight-spinner"></span><h2>正在重新连接棋盘</h2><p>飞机和道具已保存在服务器，连接后继续。</p><button class="flight-secondary" @click="emit('leave')">返回大厅</button></div></div>
      <div v-if="room.status === 'CLOSED'" class="flight-overlay"><div class="flight-modal" role="alert"><h2>房间已由管理员关闭</h2><p>这局飞行棋不再接受操作。</p><button class="flight-primary" @click="emit('leave')">返回大厅</button></div></div>
      <div v-if="error" class="flight-toast" role="alert">{{ error }}</div>
    </div>
  </main>
</template>

<style scoped>
.flight-bot-mark { display: inline-block; margin-right: 3px; padding: 0 2px; border: 1px solid #ffffffaa; border-radius: 3px; font-size: 8px; vertical-align: 1px; }
.flight-die-ready { grid-area: 1 / 1 / 4 / 4; align-self: center; }
.flight-die-caption { position: absolute; right: 0; bottom: -16px; left: 0; color: #38608c; font-size: 10px; line-height: 12px; font-weight: 600; }
</style>
