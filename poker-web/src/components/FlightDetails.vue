<script setup lang="ts">
import type { FlightRoomState } from '../types'

defineProps<{
  room: FlightRoomState
  messages: string[]
  chats: Array<{ username: string; displayName: string; text: string; sentAt: string }>
  connected: boolean
  topic?: 'rules' | 'log' | 'chat'
}>()
const chatText = defineModel<string>('chatText', { default: '' })
const emit = defineEmits<{ sendChat: [] }>()
</script>

<template>
  <section v-if="!topic || topic === 'log'" class="flight-section">
    <div class="flight-section-heading"><h2>飞行记录</h2><small>第 {{ room.game.roundNumber || '—' }} 轮</small></div>
    <ol v-if="messages.length" class="flight-log-list"><li v-for="(message, index) in messages" :key="`${index}-${message}`"><span>{{ messages.length - index }}</span>{{ message }}</li></ol>
    <p v-else class="flight-empty">选好颜色后，你们的飞行记录会显示在这里。</p>
  </section>
  <section v-if="!topic || topic === 'chat'" class="flight-section">
    <div class="flight-section-heading"><h2>和朋友聊聊</h2><small>仅实时传输</small></div>
    <div v-if="chats.length" class="flight-chat-list" aria-live="polite"><p v-for="(chat, index) in chats.slice(-10)" :key="`${chat.sentAt}-${index}`"><strong>{{ chat.displayName }}</strong><span>{{ chat.text }}</span></p></div>
    <p v-else class="flight-empty">和朋友说句话吧。聊天不保存，刷新后会清空。</p>
    <form class="flight-chat-form" @submit.prevent="emit('sendChat')"><input v-model="chatText" class="flight-chat-input" maxlength="200" :disabled="!connected || room.status !== 'ACTIVE'" aria-label="聊天内容" placeholder="和朋友说句话…" autocomplete="off" /><button class="flight-send" :disabled="!connected || room.status !== 'ACTIVE' || !chatText.trim()" aria-label="发送消息">↑</button></form>
  </section>
  <section v-if="!topic || topic === 'rules'" class="flight-section">
    <div class="flight-section-heading"><h2>游戏规则</h2><small>好友聚会</small></div>
    <ul class="flight-rule-list"><li>2～4 人，每人四架飞机。</li><li>掷出 5 或 6 可起飞，掷出 6 可继续掷骰，次数不限。</li><li>没有操作倒计时，轮到你时慢慢选。</li><li>落在同色格可跳跃，快捷航线、撞机和越界反弹按服务器规则执行。</li><li>四架飞机全部到达终点，赢得本局。</li></ul>
    <p class="flight-rule-note">神秘道具首轮不刷新，第 2、4、6……轮各尝试刷新两个，包含生成轮停留三轮，同时最多四个。拾取后效果待开放。</p>
  </section>
</template>
