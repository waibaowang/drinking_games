<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '../api'
import { botControlState } from '../bot-controls'
import type { BotSpeed, RoomState } from '../types'
import { colorNames } from '../flight-board'

const props = defineProps<{ room: RoomState; username: string; connected: boolean; busy: boolean }>()
const emit = defineEmits<{ pending: [value: boolean]; updated: [room: RoomState]; error: [cause: unknown] }>()
const pending = ref(false)
const selectedSeat = ref<number | null>(null)
const controls = computed(() => botControlState(props.room, props.username, props.connected, props.busy || pending.value))
const canAdd = computed(() => controls.value.canEdit && controls.value.hasHuman && controls.value.emptySeats.length > 0)
watch(() => controls.value.emptySeats, seats => {
  if (selectedSeat.value === null || !seats.includes(selectedSeat.value)) selectedSeat.value = seats[0] ?? null
}, { immediate: true })

function seatLabel(seat: number) { return props.room.gameType === 'FLIGHT' ? colorNames[seat] : `${seat + 1} 号座位` }
async function submit(path: string, method: 'POST' | 'DELETE' | 'PATCH', body?: unknown) {
  if (pending.value || props.busy || !props.connected || props.room.status !== 'ACTIVE' || !controls.value.host) return
  pending.value = true
  emit('pending', true)
  try {
    await api<void>(`/api/rooms/${props.room.id}/bots${path}`, { method, body: body === undefined ? undefined : JSON.stringify(body) })
    emit('updated', await api<RoomState>(`/api/rooms/${props.room.id}`))
  } catch (cause) { emit('error', cause) }
  finally { pending.value = false; emit('pending', false) }
}
function add() {
  if (canAdd.value && selectedSeat.value !== null) submit('', 'POST', { seat: selectedSeat.value })
}
function fill() { if (canAdd.value) submit('/fill', 'POST') }
function remove(id: string) {
  if (controls.value.canEdit && controls.value.bots.some(bot => bot.id === id)) submit(`/${encodeURIComponent(id)}`, 'DELETE')
}
function toggle() {
  if (controls.value.canRun) submit('/settings', 'PATCH', { enabled: !controls.value.settings.enabled })
}
function speed(event: Event) {
  const value = (event.target as HTMLSelectElement).value as BotSpeed
  if (controls.value.canRun && ['NORMAL', 'FAST'].includes(value)) submit('/settings', 'PATCH', { speed: value })
}
</script>

<template>
  <section v-if="controls.host || controls.bots.length" class="bot-controls" :class="{ 'bot-controls--flight': room.gameType === 'FLIGHT' }" aria-label="机器人设置" :aria-busy="pending">
    <header class="bot-header"><strong>机器人 <span>{{ controls.bots.length }}</span></strong><small v-if="controls.bots.length" role="status">{{ controls.settings.enabled ? '自动操作' : '已暂停' }} · {{ controls.settings.speed === 'FAST' ? '快速测试' : '正常速度' }}</small></header>
    <template v-if="controls.host">
      <p v-if="!controls.hasHuman" class="bot-hint">先选择一个空座位，再添加机器人陪你玩。</p>
      <p v-else-if="!controls.betweenGames" class="bot-hint">{{ room.gameType === 'FLIGHT' ? '本局' : '本手' }}结束后可更换机器人，暂停和速度随时可调。</p>
      <p v-else-if="!controls.emptySeats.length" class="bot-hint">座位已满，可移除机器人腾出空位。</p>
      <div class="bot-add-row">
        <select v-model="selectedSeat" aria-label="机器人座位" :disabled="!canAdd"><option v-if="!controls.emptySeats.length" :value="null">没有空位</option><option v-for="seat in controls.emptySeats" :key="seat" :value="seat">{{ seatLabel(seat) }}</option></select>
        <button type="button" :disabled="!canAdd" @click="add">添加机器人</button>
        <button type="button" :disabled="!canAdd" @click="fill">补齐空位</button>
      </div>
      <div class="bot-settings-row">
        <button type="button" :disabled="!controls.canRun" :aria-pressed="!controls.settings.enabled" @click="toggle">{{ controls.settings.enabled ? '暂停机器人' : '继续机器人' }}</button>
        <label>速度<select :value="controls.settings.speed" aria-label="机器人速度" :disabled="!controls.canRun" @change="speed"><option value="NORMAL">正常 · 0.8 秒</option><option value="FAST">快速测试 · 0.2 秒</option></select></label>
        <small v-if="pending" role="status">提交中…</small>
      </div>
    </template>
    <ul v-if="controls.bots.length" class="bot-list"><li v-for="bot in controls.bots" :key="bot.id"><span class="bot-player-name"><b class="bot-tag">BOT</b><strong>{{ bot.name }}</strong><small>{{ seatLabel(bot.seat) }}</small></span><button v-if="controls.host" type="button" :disabled="!controls.canEdit" :aria-label="`移除 ${bot.name}`" @click="remove(bot.id)">移除</button></li></ul>
    <p v-if="controls.depletedBotIds.length" class="bot-hint">有机器人筹码用完，不会自动补充。请在两手之间移除，再重新添加。</p>
    <p v-if="controls.bots.length && !controls.settings.enabled" class="bot-hint">仅暂停机器人，真人仍可操作；轮到机器人时等待继续。</p>
  </section>
</template>

<style scoped>
.bot-controls { --bot-text: #dcebe2; --bot-muted: #95afa0; --bot-border: #ffffff20; --bot-surface: #ffffff09; --bot-button: #244e3c; --bot-accent: #ebc678; margin-top: 14px; padding: 16px; color: var(--bot-text); border: 1px solid var(--bot-border); border-radius: 18px; background: #0d251deb; }
.bot-controls--flight { --bot-text: #174a8b; --bot-muted: #547ea8; --bot-border: #ffffffb8; --bot-surface: #ffffff8c; --bot-button: #d8efff; --bot-accent: #075eb5; border-radius: 23px; background: linear-gradient(145deg, #f4fcfff0, #ceebffed); box-shadow: inset 0 1px 3px #ffffffcc, 0 7px 18px #0454aa26; }
.bot-header { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; justify-content: space-between; }
.bot-header strong { font-size: 15px; }
.bot-header strong span { margin-left: 5px; color: var(--bot-muted); font-size: 12px; }
.bot-header small, .bot-hint { color: var(--bot-muted); font-size: 12px; line-height: 1.6; }
.bot-hint { margin: 10px 0 0; overflow-wrap: anywhere; }
.bot-add-row, .bot-settings-row { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.bot-controls button, .bot-controls select { min-height: 44px; max-width: 100%; padding: 8px 12px; color: var(--bot-text); border: 1px solid var(--bot-border); border-radius: 12px; background: var(--bot-surface); font: inherit; font-size: 12px; }
.bot-controls button { background: var(--bot-button); font-weight: 700; touch-action: manipulation; }
.bot-controls select { color-scheme: dark; }
.bot-controls--flight select { color-scheme: light; }
.bot-controls button:focus-visible, .bot-controls select:focus-visible { outline: 3px solid var(--bot-accent); outline-offset: 2px; }
.bot-settings-row label { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; color: var(--bot-muted); font-size: 12px; }
.bot-settings-row > small { color: var(--bot-muted); }
.bot-list { margin: 12px 0 0; padding: 0; list-style: none; }
.bot-list li { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 6px 0; border-top: 1px solid var(--bot-border); }
.bot-player-name { min-width: 0; display: flex; align-items: center; flex-wrap: wrap; gap: 6px; }
.bot-player-name strong { max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.bot-player-name small { color: var(--bot-muted); font-size: 11px; }
.bot-tag { padding: 2px 4px; color: var(--bot-accent); border: 1px solid currentColor; border-radius: 5px; font-size: 9px; }
.bot-controls button:disabled, .bot-controls select:disabled { opacity: .5; cursor: not-allowed; }
@media (max-width: 430px) { .bot-controls { padding: 13px; } .bot-add-row select { flex: 1 1 80px; } .bot-add-row button { flex: 1 1 104px; } .bot-settings-row { gap: 7px; } .bot-settings-row button { flex: 0 1 auto; } }
</style>
