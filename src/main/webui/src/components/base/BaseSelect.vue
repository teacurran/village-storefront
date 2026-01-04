<template>
  <div class="base-select-wrapper">
    <label v-if="label" :for="selectId" class="base-select-label">
      {{ label }}
      <span v-if="required" class="text-error-500">*</span>
    </label>
    <Dropdown
      :inputId="selectId"
      :modelValue="modelValue"
      :options="normalizedOptions"
      optionLabel="label"
      optionValue="value"
      :placeholder="placeholder"
      :disabled="disabled"
      :class="selectClasses"
      :showClear="!required"
      :aria-required="required || undefined"
      :pt="dropdownParts"
      @update:modelValue="handleUpdate"
    />
    <p v-if="error" class="mt-1 text-sm text-error-600">{{ error }}</p>
    <p v-else-if="hint" class="mt-1 text-sm text-neutral-500">{{ hint }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import Dropdown from 'primevue/dropdown'

export interface SelectOption {
  label: string
  value: string | number
}

export interface BaseSelectProps {
  modelValue?: string | number | null
  options: SelectOption[] | string[]
  label?: string
  placeholder?: string
  hint?: string
  error?: string
  disabled?: boolean
  required?: boolean
  size?: 'sm' | 'md' | 'lg'
}

const props = withDefaults(defineProps<BaseSelectProps>(), {
  modelValue: '',
  disabled: false,
  required: false,
  size: 'md',
})

const emit = defineEmits<{
  'update:modelValue': [value: string | number | null]
}>()

const selectId = computed(() => `select-${Math.random().toString(36).substr(2, 9)}`)

const normalizedOptions = computed<SelectOption[]>(() =>
  props.options.map((option) =>
    typeof option === 'string'
      ? { label: option, value: option }
      : option
  )
)

const selectClasses = computed(() => {
  const classes = ['base-select', 'w-full']

  // Size styles
  const sizeClasses = {
    sm: 'text-sm',
    md: 'text-base',
    lg: 'text-lg',
  }
  classes.push(sizeClasses[props.size])

  if (props.error) {
    classes.push('p-invalid')
  }
  if (props.disabled) {
    classes.push('cursor-not-allowed opacity-60')
  }

  classes.push(
    'border',
    'border-neutral-300',
    'rounded-md',
    'focus-within:ring-2',
    'focus-within:ring-primary-500',
    'focus-within:border-primary-500',
    'transition-colors',
    'duration-200'
  )

  return classes.join(' ')
})

const dropdownParts = computed(() => ({
  panel: { class: 'shadow-soft border border-neutral-200' },
}))

function handleUpdate(value: string | number | null) {
  emit('update:modelValue', value ?? null)
}
</script>

<style scoped>
.base-select-wrapper {
  @apply w-full;
}

.base-select-label {
  @apply block text-sm font-medium text-neutral-700 mb-1;
}

:deep(.p-dropdown) {
  @apply w-full border border-neutral-300 rounded-md transition-colors duration-200 shadow-none;
}

:deep(.p-dropdown:not(.p-disabled):hover) {
  @apply border-neutral-400;
}
</style>
