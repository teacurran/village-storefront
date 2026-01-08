<template>
  <div class="base-input-wrapper">
    <label v-if="label" :for="inputId" class="base-input-label">
      {{ label }}
      <span v-if="required" class="text-error-500">*</span>
    </label>
    <div class="relative">
      <InputText
        :inputId="inputId"
        :type="type"
        :modelValue="modelValue"
        :placeholder="placeholder"
        :disabled="disabled"
        :required="required"
        :class="inputClasses"
        @update:modelValue="handleInput"
        @blur="emit('blur', $event)"
        @focus="emit('focus', $event)"
      />
      <div v-if="$slots.suffix" class="absolute inset-y-0 right-0 pr-3 flex items-center">
        <slot name="suffix" />
      </div>
    </div>
    <p v-if="error" class="mt-1 text-sm text-error-600">{{ error }}</p>
    <p v-else-if="hint" class="mt-1 text-sm text-neutral-500">{{ hint }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import InputText from 'primevue/inputtext'

export interface BaseInputProps {
  modelValue?: string | number
  type?: 'text' | 'email' | 'password' | 'number' | 'tel' | 'url' | 'search'
  label?: string
  placeholder?: string
  hint?: string
  error?: string
  disabled?: boolean
  required?: boolean
  size?: 'sm' | 'md' | 'lg'
}

const props = withDefaults(defineProps<BaseInputProps>(), {
  modelValue: '',
  type: 'text',
  disabled: false,
  required: false,
  size: 'md',
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  blur: [event: FocusEvent]
  focus: [event: FocusEvent]
}>()

const inputId = computed(() => `input-${Math.random().toString(36).substr(2, 9)}`)

const inputClasses = computed(() => {
  const classes = ['base-input']

  // Size styles
  const sizeClasses = {
    sm: 'px-3 py-1.5 text-sm',
    md: 'px-4 py-2 text-base',
    lg: 'px-4 py-3 text-lg',
  }
  classes.push(sizeClasses[props.size])

  // State styles
  if (props.error) {
    classes.push(
      'border-error-500',
      'focus:ring-error-500',
      'focus:border-error-500'
    )
  } else {
    classes.push(
      'border-neutral-300',
      'focus:ring-primary-500',
      'focus:border-primary-500'
    )
  }

  if (props.disabled) {
    classes.push('bg-neutral-100', 'cursor-not-allowed', 'opacity-50')
  }

  // Common styles
  classes.push(
    'block',
    'w-full',
    'rounded-md',
    'border',
    'focus:outline-none',
    'focus:ring-2',
    'transition-colors',
    'duration-200'
  )

  return classes.join(' ')
})

function handleInput(value: string) {
  emit('update:modelValue', value)
}
</script>

<style scoped>
.base-input-wrapper {
  @apply w-full;
}

.base-input-label {
  @apply block text-sm font-medium text-neutral-700 mb-1;
}
</style>
