<template>
  <span :class="badgeClasses">{{ status }}</span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  status: string
}>()

const badgeClasses = computed(() => {
  const base = 'px-2 py-1 text-xs font-medium rounded-full uppercase'
  switch (props.status.toLowerCase()) {
    case 'pending':
      return `${base} bg-yellow-100 text-yellow-800`
    case 'running':
      return `${base} bg-blue-100 text-blue-800 animate-pulse`
    case 'completed':
      return `${base} bg-green-100 text-green-800`
    case 'failed':
      return `${base} bg-red-100 text-red-800`
    default:
      return `${base} bg-neutral-100 text-neutral-800`
  }
})
</script>
