<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import type { HistoryItem, HistoryPage } from '../types'

const data = ref<HistoryPage | null>(null)
const selected = ref<HistoryItem | null>(null)
const error = ref('')
const loading = ref(true)

async function load(page = 0) {
  error.value = ''; loading.value = true; selected.value = null
  try { data.value = await api<HistoryPage>(`/api/history?page=${page}&size=20`) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '加载失败' }
  finally { loading.value = false }
}
async function detail(id: string) {
  try { selected.value = await api<HistoryItem>(`/api/history/${id}`) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '加载失败' }
}
onMounted(() => load())
</script>

<template>
  <section class="page-section">
    <div class="page-heading"><div><p class="eyebrow">HAND HISTORY</p><h1>牌局历史</h1></div><span class="muted">只显示你参与过的手牌</span></div>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <div v-if="loading" class="empty-state panel">正在加载牌局历史…</div>
    <div v-else-if="data?.items.length" class="history-layout">
      <div class="history-list panel">
        <button v-for="item in data.items" :key="item.id" :class="{ selected: selected?.id === item.id }" @click="detail(item.id)">
          <span><strong>{{ item.roomName }}</strong><small>第 {{ item.handNumber }} 手</small></span>
          <time>{{ new Date(item.completedAt).toLocaleString() }}</time>
        </button>
        <div class="pager"><button class="ghost" :disabled="data.page === 0" @click="load(data.page - 1)">上一页</button><span>{{ data.page + 1 }} / {{ Math.max(1, data.totalPages) }}</span><button class="ghost" :disabled="data.page + 1 >= data.totalPages" @click="load(data.page + 1)">下一页</button></div>
      </div>
      <article class="history-detail panel"><template v-if="selected"><p class="eyebrow">SETTLEMENT</p><h2>{{ selected.roomName }} · 第 {{ selected.handNumber }} 手</h2><pre>{{ selected.summary }}</pre></template><div v-else class="empty-inline">选择一手牌查看结算摘要</div></article>
    </div>
    <div v-else class="empty-state panel"><div class="empty-card">♣</div><h3>还没有历史记录</h3><p>完成一手牌后，结算摘要会出现在这里。</p></div>
  </section>
</template>
