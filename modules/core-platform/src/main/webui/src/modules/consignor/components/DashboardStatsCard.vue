<template>
  <div class="stats-card">
    <div class="stats-card-icon" :class="iconClasses">
      <span class="text-2xl">{{ icon }}</span>
    </div>
    <div class="stats-card-content">
      <p class="stats-card-label">{{ label }}</p>
      <p class="stats-card-value">{{ value }}</p>
      <p v-if="subtitle" class="stats-card-subtitle">{{ subtitle }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  icon: string
  label: string
  value: string | number
  subtitle?: string
  color?: 'primary' | 'secondary' | 'success' | 'warning' | 'error'
}>()

const COLOR_CLASS_MAP: Record<string, string> = {
  primary: 'bg-primary-100 text-primary-600',
  secondary: 'bg-secondary-100 text-secondary-600',
  success: 'bg-success-100 text-success-700',
  warning: 'bg-warning-100 text-warning-700',
  error: 'bg-error-100 text-error-700',
}

const iconClasses = computed(() => COLOR_CLASS_MAP[props.color || 'primary'])
</script>

<style scoped>
.stats-card {
  @apply bg-white rounded-lg shadow-soft p-6 flex items-start gap-4;
}

.stats-card-icon {
  @apply w-12 h-12 rounded-lg flex items-center justify-center flex-shrink-0;
}

.stats-card-content {
  @apply flex-1 min-w-0;
}

.stats-card-label {
  @apply text-sm font-medium text-neutral-600 mb-1;
}

.stats-card-value {
  @apply text-2xl font-bold text-neutral-900 mb-0.5;
}

.stats-card-subtitle {
  @apply text-sm text-neutral-500;
}
</style>
