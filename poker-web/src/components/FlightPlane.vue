<script setup lang="ts">
import { computed, useId } from 'vue'
import { colors } from '../flight-board'

const props = defineProps<{ seat: number; number?: number; silhouette?: boolean }>()
const id = `flight-plane-${useId().replace(/:/g, '')}`
const darkColors = ['#b6292c', '#b97c0a', '#166db2', '#23824a']
const color = computed(() => colors[props.seat])
const dark = computed(() => darkColors[props.seat])
</script>

<template>
  <g class="flight-plane" :class="{ silhouette }" aria-hidden="true">
    <defs>
      <linearGradient :id="`${id}-wing`" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" :stop-color="color" />
        <stop offset="1" :stop-color="dark" />
      </linearGradient>
      <linearGradient :id="`${id}-body`" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0" :stop-color="dark" />
        <stop offset="0.4" :stop-color="color" />
        <stop offset="0.65" :stop-color="color" />
        <stop offset="1" :stop-color="dark" />
      </linearGradient>
    </defs>
    <g class="plane-airframe" :style="{ color: dark }">
      <path class="plane-wing" :fill="`url(#${id}-wing)`" d="M-4-7-24 10Q-26 13-24 15L-5 11H5L24 15Q26 13 24 10L4-7Z" />
      <path :fill="`url(#${id}-wing)`" d="M-3 20-11 28Q-13 31-10 31L0 28 10 31Q13 31 11 28L3 20Z" />
      <path class="plane-body" :fill="`url(#${id}-body)`" d="M0-34C-5-34-6-25-6-16L-4 24Q-3 33 0 34Q3 33 4 24L6-16C6-25 5-34 0-34Z" />
      <path class="plane-cockpit" d="M0-29C-3-29-3-25-3-21Q-3-18 0-18Q3-18 3-21C3-25 3-29 0-29Z" />
      <path class="plane-seam" d="M0-15V29" />
    </g>
    <g v-if="number !== undefined && !silhouette" class="plane-number">
      <circle cy="8" r="9" />
      <text x="0" y="13" text-anchor="middle">{{ number }}</text>
    </g>
  </g>
</template>

<style scoped>
.plane-airframe { filter: drop-shadow(0 2px 1.5px #09274855); }
.plane-wing, .plane-body { stroke: currentColor; stroke-width: 0.65; }
.plane-cockpit { fill: #092434; stroke: #ffffff4d; stroke-width: 0.6; }
.plane-seam { fill: none; stroke: #ffffff58; stroke-width: 0.65; }
.plane-number circle { fill: #fffef7; stroke: #ffffffb3; stroke-width: 1; }
.plane-number text { fill: #153045; font: 800 14px system-ui, sans-serif; }
.silhouette { opacity: 0.2; }
.silhouette .plane-airframe { filter: none; }
.silhouette .plane-airframe path { fill: currentColor; stroke: none; }
.silhouette .plane-seam, .silhouette .plane-cockpit { display: none; }
</style>
