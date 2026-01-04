<template>
  <Button
    :type="type"
    :severity="severity"
    :loading="loading"
    :disabled="disabled || loading"
    :class="buttonClasses"
    @click="handleClick"
  >
    <slot />
  </Button>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import Button from 'primevue/button'

export interface BaseButtonProps {
  variant?: 'primary' | 'secondary' | 'success' | 'warning' | 'error' | 'neutral'
  size?: 'sm' | 'md' | 'lg'
  type?: 'button' | 'submit' | 'reset'
  disabled?: boolean
  loading?: boolean
  fullWidth?: boolean
}

const props = withDefaults(defineProps<BaseButtonProps>(), {
  variant: 'primary',
  size: 'md',
  type: 'button',
  disabled: false,
  loading: false,
  fullWidth: false,
})

const emit = defineEmits<{
  click: [event: MouseEvent]
}>()

const severity = computed(() => {
  const map: Record<BaseButtonProps['variant'], string> = {
    primary: 'primary',
    secondary: 'secondary',
    success: 'success',
    warning: 'warning',
    error: 'danger',
    neutral: 'secondary',
  }
  return map[props.variant]
})

const buttonClasses = computed(() => {
  const classes = ['base-button', 'shadow-none', 'font-medium']

  const sizeClasses = {
    sm: 'px-3 py-1.5 text-sm',
    md: 'px-4 py-2 text-base',
    lg: 'px-6 py-3 text-lg',
  }
  classes.push(sizeClasses[props.size])

  if (props.fullWidth) {
    classes.push('w-full')
  }

  if (props.disabled || props.loading) {
    classes.push('cursor-not-allowed opacity-60')
  }

  classes.push(
    'transition-all',
    'duration-200',
    'focus:outline-none',
    'focus:ring-2',
    'focus:ring-offset-2',
    'focus:ring-primary-500'
  )

  return classes.join(' ')
})

function handleClick(event: MouseEvent) {
  if (!props.disabled && !props.loading) {
    emit('click', event)
  }
}
</script>
