<template>
  <div :class="badgeClasses">
    <span class="indicator-dot" :class="dotClasses" />
    <span class="text-xs">{{ freshnessText }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  timestamp: string
}>()

interface FreshnessResult {
  lag: number
  level: 'fresh' | 'stale' | 'old'
}

function computeFreshnessLag(timestamp: string): FreshnessResult {
  const now = Date.now()
  const freshness = new Date(timestamp).getTime()
  const lagSeconds = (now - freshness) / 1000

  let level: 'fresh' | 'stale' | 'old'
  if (lagSeconds < 300) {
    level = 'fresh' // < 5 min
  } else if (lagSeconds < 900) {
    level = 'stale' // < 15 min
  } else {
    level = 'old' // > 15 min
  }

  return { lag: lagSeconds, level }
}

function formatTimeAgo(seconds: number): string {
  if (seconds < 60) {
    return 'just now'
  }
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) {
    return `${minutes} ${minutes === 1 ? 'minute' : 'minutes'} ago`
  }
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    return `${hours} ${hours === 1 ? 'hour' : 'hours'} ago`
  }
  const days = Math.floor(hours / 24)
  return `${days} ${days === 1 ? 'day' : 'days'} ago`
}

const freshness = computed(() => computeFreshnessLag(props.timestamp))

const freshnessText = computed(() => {
  return `Updated ${formatTimeAgo(freshness.value.lag)}`
})

const badgeClasses = computed(() => {
  const base = 'flex items-center gap-1.5 px-2 py-1 rounded-full text-xs font-medium'
  switch (freshness.value.level) {
    case 'fresh':
      return `${base} bg-green-50 text-green-700`
    case 'stale':
      return `${base} bg-yellow-50 text-yellow-700`
    case 'old':
      return `${base} bg-red-50 text-red-700`
    default:
      return `${base} bg-neutral-50 text-neutral-700`
  }
})

const dotClasses = computed(() => {
  switch (freshness.value.level) {
    case 'fresh':
      return 'bg-green-500'
    case 'stale':
      return 'bg-yellow-500'
    case 'old':
      return 'bg-red-500'
    default:
      return 'bg-neutral-500'
  }
})
</script>

<style scoped>
.indicator-dot {
  @apply w-2 h-2 rounded-full;
}
</style>
