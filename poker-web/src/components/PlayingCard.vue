<script setup lang="ts">
import type { Card } from '../types'

defineProps<{ card?: Card; hidden?: boolean; compact?: boolean }>()

const ranks: Record<string, string> = {
  TWO: '2', THREE: '3', FOUR: '4', FIVE: '5', SIX: '6', SEVEN: '7', EIGHT: '8',
  NINE: '9', TEN: '10', JACK: 'J', QUEEN: 'Q', KING: 'K', ACE: 'A',
}
const suits: Record<string, string> = { CLUBS: '♣', DIAMONDS: '♦', HEARTS: '♥', SPADES: '♠' }
</script>

<template>
  <div class="playing-card" :class="[{ hidden, compact }, card?.suit?.toLowerCase()]" :aria-label="hidden ? '一张盖住的牌' : `${ranks[card?.rank ?? '']} ${suits[card?.suit ?? '']}`">
    <template v-if="card && !hidden">
      <span class="rank">{{ ranks[card.rank] }}</span>
      <span class="suit">{{ suits[card.suit] }}</span>
    </template>
    <span v-else class="card-mark">H</span>
  </div>
</template>

