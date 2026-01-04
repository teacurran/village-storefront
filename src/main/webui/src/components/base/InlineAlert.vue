<template>
  <div v-if="!dismissed" :class="alertClasses" role="alert">
    <div class="flex items-start">
      <div class="flex-shrink-0">
        <component :is="icon" class="alert-icon" />
      </div>
      <div class="ml-3 flex-1">
        <h3 v-if="title" class="alert-title">{{ title }}</h3>
        <div class="alert-description">
          <slot>{{ description }}</slot>
        </div>
        <div v-if="actions.length" class="alert-actions">
          <button
            v-for="(action, index) in actions"
            :key="index"
            type="button"
            :class="actionButtonClasses"
            @click="action.onClick"
          >
            {{ action.label }}
          </button>
        </div>
      </div>
      <div v-if="dismissible" class="ml-auto pl-3">
        <button type="button" class="alert-dismiss" @click="handleDismiss">
          <span class="sr-only">Dismiss</span>
          <svg class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
            <path
              fill-rule="evenodd"
              d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z"
              clip-rule="evenodd"
            />
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'

export interface AlertAction {
  label: string
  onClick: () => void
}

export interface InlineAlertProps {
  tone?: 'info' | 'success' | 'warning' | 'error'
  title?: string
  description?: string
  actions?: AlertAction[]
  dismissible?: boolean
  persistDismissal?: boolean
}

const props = withDefaults(defineProps<InlineAlertProps>(), {
  tone: 'info',
  actions: () => [],
  dismissible: false,
  persistDismissal: false,
})

const emit = defineEmits<{
  dismiss: []
}>()

const dismissed = ref(false)

const alertClasses = computed(() => {
  const classes = ['inline-alert']

  const toneClasses = {
    info: 'bg-primary-50 border-primary-200',
    success: 'bg-success-50 border-success-200',
    warning: 'bg-warning-50 border-warning-200',
    error: 'bg-error-50 border-error-200',
  }
  classes.push(toneClasses[props.tone])

  return classes.join(' ')
})

const icon = computed(() => {
  // Return appropriate icon component based on tone
  // For now, using simple SVG elements
  const icons = {
    info: InfoIcon,
    success: SuccessIcon,
    warning: WarningIcon,
    error: ErrorIcon,
  }
  return icons[props.tone]
})

const actionButtonClasses = computed(() => {
  const baseClasses = 'text-sm font-medium underline hover:no-underline mr-4'

  const toneClasses = {
    info: 'text-primary-700',
    success: 'text-success-700',
    warning: 'text-warning-700',
    error: 'text-error-700',
  }

  return `${baseClasses} ${toneClasses[props.tone]}`
})

function handleDismiss() {
  dismissed.value = true
  emit('dismiss')

  if (props.persistDismissal && props.title) {
    // Store dismissal in localStorage
    localStorage.setItem(`alert-dismissed-${props.title}`, 'true')
  }
}

// Icon components
const InfoIcon = {
  template: `
    <svg class="h-5 w-5 text-primary-600" viewBox="0 0 20 20" fill="currentColor">
      <path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clip-rule="evenodd" />
    </svg>
  `,
}

const SuccessIcon = {
  template: `
    <svg class="h-5 w-5 text-success-600" viewBox="0 0 20 20" fill="currentColor">
      <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" />
    </svg>
  `,
}

const WarningIcon = {
  template: `
    <svg class="h-5 w-5 text-warning-600" viewBox="0 0 20 20" fill="currentColor">
      <path fill-rule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clip-rule="evenodd" />
    </svg>
  `,
}

const ErrorIcon = {
  template: `
    <svg class="h-5 w-5 text-error-600" viewBox="0 0 20 20" fill="currentColor">
      <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd" />
    </svg>
  `,
}
</script>

<style scoped>
.inline-alert {
  @apply rounded-md border p-4;
}

.alert-icon {
  @apply h-5 w-5;
}

.alert-title {
  @apply text-sm font-medium mb-1;
}

.alert-title {
  color: inherit;
}

.alert-description {
  @apply text-sm;
  color: inherit;
}

.alert-actions {
  @apply mt-3;
}

.alert-dismiss {
  @apply -m-1.5 p-1.5 rounded-md hover:bg-black hover:bg-opacity-10 transition-colors;
}

.sr-only {
  @apply absolute w-px h-px p-0 -m-px overflow-hidden whitespace-nowrap border-0;
  clip: rect(0, 0, 0, 0);
}

.inline-alert.bg-primary-50 .alert-title,
.inline-alert.bg-primary-50 .alert-description {
  @apply text-primary-800;
}

.inline-alert.bg-success-50 .alert-title,
.inline-alert.bg-success-50 .alert-description {
  @apply text-success-800;
}

.inline-alert.bg-warning-50 .alert-title,
.inline-alert.bg-warning-50 .alert-description {
  @apply text-warning-800;
}

.inline-alert.bg-error-50 .alert-title,
.inline-alert.bg-error-50 .alert-description {
  @apply text-error-800;
}
</style>
