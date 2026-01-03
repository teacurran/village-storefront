<template>
  <div class="metrics-card">
    <div class="metrics-card-header">
      <h3 class="metrics-card-title">{{ title }}</h3>
      <slot name="actions" />
    </div>
    <div class="metrics-card-body">
      <div class="metrics-value-container">
        <span class="metrics-value">{{ formattedValue }}</span>
        <span v-if="change !== undefined" :class="changeClasses">
          <span class="change-icon">{{ changeIcon }}</span>
          <span>{{ Math.abs(change) }}%</span>
        </span>
      </div>
      <p v-if="timeframe" class="metrics-timeframe">{{ timeframe }}</p>
      <div v-if="showSparkline && sparklineData.length" class="mt-3">
        <svg :viewBox="`0 0 ${sparklineData.length * 10} 50`" class="sparkline">
          <polyline
            :points="sparklinePoints"
            fill="none"
            :stroke="sparklineColor"
            stroke-width="2"
          />
        </svg>
      </div>
    </div>
    <div v-if="$slots.footer" class="metrics-card-footer">
      <slot name="footer" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

export interface MetricsCardProps {
  title: string
  value: number | string
  change?: number
  timeframe?: string
  format?: 'number' | 'currency' | 'percentage'
  currency?: string
  showSparkline?: boolean
  sparklineData?: number[]
}

const props = withDefaults(defineProps<MetricsCardProps>(), {
  format: 'number',
  currency: 'USD',
  showSparkline: false,
  sparklineData: () => [],
})

const formattedValue = computed(() => {
  if (typeof props.value === 'string') return props.value

  switch (props.format) {
    case 'currency':
      return new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency: props.currency,
      }).format(props.value)
    case 'percentage':
      return `${props.value}%`
    default:
      return new Intl.NumberFormat('en-US').format(props.value)
  }
})

const changeClasses = computed(() => {
  const classes = ['metrics-change']
  if (props.change === undefined) return classes

  if (props.change > 0) {
    classes.push('text-success-600')
  } else if (props.change < 0) {
    classes.push('text-error-600')
  } else {
    classes.push('text-neutral-500')
  }

  return classes.join(' ')
})

const changeIcon = computed(() => {
  if (props.change === undefined || props.change === 0) return '→'
  return props.change > 0 ? '↑' : '↓'
})

const sparklineColor = computed(() => {
  if (props.change === undefined) return '#3b82f6'
  return props.change >= 0 ? '#22c55e' : '#ef4444'
})

const sparklinePoints = computed(() => {
  if (!props.sparklineData.length) return ''

  const max = Math.max(...props.sparklineData)
  const min = Math.min(...props.sparklineData)
  const range = max - min || 1

  return props.sparklineData
    .map((value, index) => {
      const x = index * 10 + 5
      const y = 45 - ((value - min) / range) * 40
      return `${x},${y}`
    })
    .join(' ')
})
</script>

<style scoped>
.metrics-card {
  @apply bg-white rounded-lg shadow-soft p-6 border border-neutral-200;
}

.metrics-card-header {
  @apply flex items-center justify-between mb-4;
}

.metrics-card-title {
  @apply text-sm font-medium text-neutral-600;
}

.metrics-card-body {
  @apply space-y-1;
}

.metrics-value-container {
  @apply flex items-baseline gap-3;
}

.metrics-value {
  @apply text-3xl font-bold text-neutral-900;
}

.metrics-change {
  @apply text-sm font-medium flex items-center gap-1;
}

.change-icon {
  @apply text-base;
}

.metrics-timeframe {
  @apply text-xs text-neutral-500;
}

.metrics-card-footer {
  @apply mt-4 pt-4 border-t border-neutral-200;
}

.sparkline {
  @apply w-full h-12;
}
</style>
