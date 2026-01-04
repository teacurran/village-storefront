<template>
  <Dialog :visible="visible" modal :header="t('inventory.adjustDialog.title')" @hide="emit('close')">
    <div class="dialog-body">
      <label class="field-label" for="quantity">{{ t('inventory.adjustDialog.quantity') }}</label>
      <InputNumber
        input-id="quantity"
        v-model.number="quantity"
        :min="-100"
        :max="100"
        mode="decimal"
        show-buttons
        class="w-full"
      />

      <label class="field-label" for="reason">{{ t('inventory.adjustDialog.reason') }}</label>
      <Dropdown
        input-id="reason"
        v-model="reason"
        :options="reasonOptions"
        option-label="label"
        option-value="value"
        class="w-full"
      />

      <label class="field-label" for="notes">{{ t('inventory.adjustDialog.notes') }}</label>
      <Textarea id="notes" v-model="notes" auto-resize rows="3" />
    </div>

    <template #footer>
      <Button class="p-button-text" :label="t('common.cancel')" @click="emit('close')" />
      <Button
        :label="t('inventory.adjustDialog.save')"
        :disabled="!quantity || !reason"
        @click="handleSave"
      />
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import Dropdown from 'primevue/dropdown'
import InputNumber from 'primevue/inputnumber'
import Textarea from 'primevue/textarea'
import { useI18n } from '@/composables/useI18n'

const props = defineProps<{ visible: boolean }>()

const emit = defineEmits<{
  close: []
  save: [payload: { quantityChange: number; reason: string; notes?: string }]
}>()

const { t } = useI18n()
const quantity = ref<number | null>(null)
const reason = ref<string | null>(null)
const notes = ref('')

const reasonOptions = [
  { label: t('inventory.adjustDialog.reasons.damage'), value: 'damage' },
  { label: t('inventory.adjustDialog.reasons.cycleCount'), value: 'cycle_count' },
  { label: t('inventory.adjustDialog.reasons.returned'), value: 'customer_return' },
]

function handleSave() {
  if (!quantity.value || !reason.value) return
  emit('save', {
    quantityChange: quantity.value,
    reason: reason.value,
    notes: notes.value || undefined,
  })
  quantity.value = null
  reason.value = null
  notes.value = ''
}
</script>

<style scoped>
.dialog-body {
  @apply space-y-3;
}

.field-label {
  @apply text-sm font-medium text-neutral-700;
}
</style>
