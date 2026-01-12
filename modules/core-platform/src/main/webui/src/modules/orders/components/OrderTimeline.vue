<template>
  <div class="order-timeline">
    <header class="timeline-header">
      <h3 class="timeline-title">{{ t('orders.timeline.title') }}</h3>
      <div v-if="showFilter" class="timeline-filter">
        <select v-model="filterType" class="filter-select" @change="handleFilterChange">
          <option value="">{{ t('orders.timeline.allEvents') }}</option>
          <option value="status">{{ t('orders.timeline.statusChanges') }}</option>
          <option value="payment">{{ t('orders.timeline.paymentEvents') }}</option>
          <option value="note">{{ t('orders.timeline.notes') }}</option>
          <option value="system">{{ t('orders.timeline.systemEvents') }}</option>
        </select>
      </div>
    </header>

    <!-- Empty State -->
    <div v-if="!filteredEvents.length" class="timeline-empty">
      <p>{{ t('orders.timeline.noEvents') }}</p>
    </div>

    <!-- Timeline Events -->
    <ul v-else class="timeline-list" role="list">
      <li
        v-for="event in filteredEvents"
        :key="event.id"
        class="timeline-item"
        :class="{ 'has-attachment': hasAttachment(event) }"
      >
        <!-- Event Marker -->
        <div class="timeline-marker" :class="`marker-${getEventCategory(event.type)}`">
          <div class="marker-dot" />
        </div>

        <!-- Event Content -->
        <div class="timeline-content">
          <!-- Event Header -->
          <div class="event-header">
            <h4 class="event-title">{{ event.description }}</h4>
            <span v-if="showActor && event.actor" class="event-actor">
              {{ formatActor(event.actor) }}
            </span>
          </div>

          <!-- Event Meta -->
          <div class="event-meta">
            <time :datetime="event.timestamp" class="event-time">
              {{ formatTimestamp(event.timestamp) }}
            </time>
            <span v-if="event.metadata?.impersonated" class="impersonation-badge">
              {{ t('orders.timeline.impersonated') }}
            </span>
          </div>

          <!-- Event Details -->
          <div v-if="event.metadata && hasVisibleMetadata(event)" class="event-details">
            <dl class="details-list">
              <template v-for="(value, key) in getVisibleMetadata(event)" :key="key">
                <dt class="detail-label">{{ formatMetadataKey(key) }}</dt>
                <dd class="detail-value">{{ formatMetadataValue(value) }}</dd>
              </template>
            </dl>
          </div>

          <!-- Attachments Preview -->
          <div v-if="hasAttachment(event)" class="event-attachment">
            <button
              class="attachment-preview"
              :aria-label="t('orders.timeline.viewAttachment')"
              @click="() => $emit('view-attachment', event)"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13"
                />
              </svg>
              <span>{{ event.metadata.attachmentName || t('orders.timeline.attachment') }}</span>
            </button>
          </div>
        </div>
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from '@/composables/useI18n'
import type { OrderEvent } from '../types'

const props = defineProps<{
  events: OrderEvent[]
  filter?: string
  showActor?: boolean
  showFilter?: boolean
}>()

const emit = defineEmits<{
  'view-attachment': [event: OrderEvent]
  'filter-change': [filterType: string]
}>()

const { t } = useI18n()

const filterType = ref(props.filter || '')

const filteredEvents = computed(() => {
  if (!filterType.value) {
    return props.events
  }

  return props.events.filter((event) => {
    const category = getEventCategory(event.type)
    return category === filterType.value
  })
})

function getEventCategory(type: string): string {
  // Map event types to categories for filtering and styling
  if (type.includes('status')) return 'status'
  if (type.includes('payment') || type.includes('refund') || type.includes('capture'))
    return 'payment'
  if (type.includes('note') || type.includes('comment')) return 'note'
  if (type.includes('created') || type.includes('updated')) return 'system'
  return 'system'
}

function hasAttachment(event: OrderEvent): boolean {
  return !!(event.metadata?.attachmentUrl || event.metadata?.attachmentName)
}

function hasVisibleMetadata(event: OrderEvent): boolean {
  if (!event.metadata) return false

  const hiddenKeys = new Set(['impersonated', 'attachmentUrl', 'attachmentName', 'internalOnly'])

  return Object.keys(event.metadata).some((key) => !hiddenKeys.has(key))
}

function getVisibleMetadata(event: OrderEvent): Record<string, any> {
  if (!event.metadata) return {}

  const hiddenKeys = new Set(['impersonated', 'attachmentUrl', 'attachmentName', 'internalOnly'])

  return Object.entries(event.metadata)
    .filter(([key]) => !hiddenKeys.has(key))
    .reduce((acc, [key, value]) => ({ ...acc, [key]: value }), {})
}

function formatActor(actor: string): string {
  // Format actor display name
  if (actor.includes('@')) {
    return actor // Email address
  }
  if (actor === 'system') {
    return t('orders.timeline.systemActor')
  }
  return actor
}

function formatTimestamp(timestamp: string): string {
  const date = new Date(timestamp)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMins = Math.floor(diffMs / 60000)

  // Show relative time for recent events
  if (diffMins < 1) {
    return t('orders.timeline.justNow')
  }
  if (diffMins < 60) {
    return t('orders.timeline.minutesAgo', { count: diffMins })
  }
  if (diffMins < 1440) {
    const hours = Math.floor(diffMins / 60)
    return t('orders.timeline.hoursAgo', { count: hours })
  }

  // Show formatted date for older events
  return new Intl.DateTimeFormat('en-US', {
    month: 'short',
    day: 'numeric',
    year: date.getFullYear() !== now.getFullYear() ? 'numeric' : undefined,
    hour: 'numeric',
    minute: '2-digit',
  }).format(date)
}

function formatMetadataKey(key: string): string {
  // Convert camelCase to Title Case
  return key
    .replace(/([A-Z])/g, ' $1')
    .replace(/^./, (str) => str.toUpperCase())
    .trim()
}

function formatMetadataValue(value: any): string {
  if (typeof value === 'object') {
    return JSON.stringify(value, null, 2)
  }
  return String(value)
}

function handleFilterChange() {
  emit('filter-change', filterType.value)
}
</script>

<style scoped>
.order-timeline {
  @apply w-full;
}

.timeline-header {
  @apply flex items-center justify-between mb-4;
}

.timeline-title {
  @apply text-lg font-semibold text-neutral-900;
}

.timeline-filter {
  @apply flex items-center gap-2;
}

.filter-select {
  @apply px-3 py-1.5 text-sm border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500;
}

.timeline-empty {
  @apply py-8 text-center text-neutral-500;
}

.timeline-list {
  @apply space-y-6;
}

.timeline-item {
  @apply relative flex gap-4;
}

.timeline-item:not(:last-child)::before {
  content: '';
  @apply absolute left-[7px] top-8 w-px h-full bg-neutral-200;
}

.timeline-marker {
  @apply flex-shrink-0 mt-1;
}

.marker-dot {
  @apply w-4 h-4 rounded-full border-2 bg-white;
}

.marker-status .marker-dot {
  @apply border-primary-500;
}

.marker-payment .marker-dot {
  @apply border-success-500 bg-success-50;
}

.marker-note .marker-dot {
  @apply border-warning-500 bg-warning-50;
}

.marker-system .marker-dot {
  @apply border-neutral-400;
}

.timeline-content {
  @apply flex-1 pb-2;
}

.event-header {
  @apply flex items-start justify-between gap-3 mb-1;
}

.event-title {
  @apply text-sm font-medium text-neutral-900;
}

.event-actor {
  @apply text-xs text-neutral-500 whitespace-nowrap;
}

.event-meta {
  @apply flex items-center gap-2 mb-2;
}

.event-time {
  @apply text-xs text-neutral-500;
}

.impersonation-badge {
  @apply px-1.5 py-0.5 text-xs font-medium bg-warning-100 text-warning-800 rounded;
}

.event-details {
  @apply mt-2 p-3 bg-neutral-50 rounded border border-neutral-200;
}

.details-list {
  @apply grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm;
}

.detail-label {
  @apply font-medium text-neutral-700;
}

.detail-value {
  @apply text-neutral-900;
}

.event-attachment {
  @apply mt-2;
}

.attachment-preview {
  @apply flex items-center gap-2 px-3 py-2 text-sm text-primary-700 bg-primary-50 hover:bg-primary-100 rounded border border-primary-200 transition-colors;
}
</style>
