<!--
  ContrastBadge Component

  Displays WCAG 2.1 contrast ratio compliance badge.
  Shows pass/fail status for Level AA (4.5:1) and AAA (7:1) requirements.
-->

<template>
  <div class="contrast-badge" :class="badgeClass" :title="title">
    <span class="badge-text">{{ displayLabel }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  ratio: number
  label?: string
  size?: 'sm' | 'md' | 'lg'
}

const props = withDefaults(defineProps<Props>(), {
  label: 'AA',
  size: 'md',
})

// WCAG 2.1 contrast requirements
const AA_NORMAL = 4.5
const AA_LARGE = 3.0
const AAA_NORMAL = 7.0
const AAA_LARGE = 4.5

const passesAA = computed(() => props.ratio >= AA_NORMAL)
const passesAAA = computed(() => props.ratio >= AAA_NORMAL)

const badgeClass = computed(() => {
  const sizeClass = {
    sm: 'badge-sm',
    md: 'badge-md',
    lg: 'badge-lg',
  }[props.size]

  const statusClass = passesAA.value ? 'badge-pass' : 'badge-fail'

  return [sizeClass, statusClass]
})

const displayLabel = computed(() => {
  if (passesAAA.value) {
    return 'AAA'
  }
  if (passesAA.value) {
    return 'AA'
  }
  return 'FAIL'
})

const title = computed(() => {
  const ratioText = `Contrast ratio: ${props.ratio.toFixed(2)}:1`
  const statusText = passesAA.value
    ? passesAAA.value
      ? 'Passes WCAG Level AAA'
      : 'Passes WCAG Level AA'
    : 'Fails WCAG Level AA'
  return `${ratioText} - ${statusText}`
})
</script>

<style scoped>
.contrast-badge {
  @apply inline-flex items-center justify-center rounded font-mono font-semibold;
  @apply transition-colors;
}

.badge-sm {
  @apply px-1 py-0.5 text-xs;
}

.badge-md {
  @apply px-2 py-1 text-sm;
}

.badge-lg {
  @apply px-3 py-1.5 text-base;
}

.badge-pass {
  @apply bg-success-100 text-success-800 border border-success-300;
}

.badge-fail {
  @apply bg-error-100 text-error-800 border border-error-300;
}
</style>
