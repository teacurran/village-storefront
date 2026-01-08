<template>
  <div class="notifications-dashboard">
    <div class="dashboard-header">
      <div>
        <h1 class="dashboard-title">{{ t('notifications.title') }}</h1>
        <p class="dashboard-subtitle">{{ t('notifications.subtitle') }}</p>
      </div>
      <div class="header-actions">
        <div class="sse-badge" :class="{ connected: notificationsStore.sseConnected }">
          <span class="dot" />
          <span>{{ notificationsStore.sseConnected ? t('common.live') : t('common.offline') }}</span>
        </div>
        <Button
          v-if="notificationsStore.unreadCount > 0"
          class="p-button-text"
          :label="t('notifications.actions.markAll')"
          @click="notificationsStore.markAllAsRead"
        />
      </div>
    </div>

    <div class="filters-card">
      <Dropdown
        :options="severityOptions"
        option-label="label"
        option-value="value"
        v-model="selectedSeverity"
        class="w-48"
      />
      <InputText
        v-model="search"
        :placeholder="t('notifications.filters.search')"
        class="flex-1"
      />
    </div>

    <div class="notifications-list">
      <article
        v-for="notification in notificationsStore.filteredNotifications"
        :key="notification.id"
        :class="['notification-card', notification.severity.toLowerCase(), { unread: !notification.read }]"
      >
        <div>
          <p class="eyebrow">{{ formatSeverity(notification.severity) }}</p>
          <h3>{{ notification.title }}</h3>
          <p class="message">{{ notification.message }}</p>
          <p class="timestamp">{{ new Date(notification.createdAt).toLocaleString() }}</p>
        </div>
        <div class="card-actions">
          <Button
            v-if="!notification.read"
            class="p-button-text"
            :label="t('notifications.actions.markRead')"
            @click="notificationsStore.markAsRead(notification.id)"
          />
          <Button
            v-if="notification.actionUrl"
            class="p-button-text"
            :label="t('notifications.actions.open')"
            @click="openAction(notification.actionUrl)"
          />
        </div>
      </article>
      <div v-if="!notificationsStore.filteredNotifications.length" class="empty-state">
        {{ t('notifications.emptyState') }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Dropdown from 'primevue/dropdown'
import { useNotificationsStore } from '../store'
import { useAuthStore } from '@/stores/auth'
import { useI18n } from '@/composables/useI18n'

const notificationsStore = useNotificationsStore()
const authStore = useAuthStore()
const { t } = useI18n()

const selectedSeverity = ref<'ALL' | 'INFO' | 'SUCCESS' | 'WARNING' | 'ERROR'>('ALL')
const search = ref('')

const severityOptions = [
  { label: t('notifications.filters.all'), value: 'ALL' },
  { label: t('notifications.filters.error'), value: 'ERROR' },
  { label: t('notifications.filters.warning'), value: 'WARNING' },
  { label: t('notifications.filters.success'), value: 'SUCCESS' },
  { label: t('notifications.filters.info'), value: 'INFO' },
]

onMounted(async () => {
  authStore.restoreAuth()
  if (!authStore.hasRole('NOTIFICATIONS_VIEW')) return
  await notificationsStore.loadNotifications()
  notificationsStore.connectSSE()
})

onBeforeUnmount(() => {
  notificationsStore.disconnectSSE()
})

watch(selectedSeverity, (value) => notificationsStore.setSeverityFilter(value))
watch(search, (value) => notificationsStore.setSearch(value))

function formatSeverity(severity: string) {
  return t(`notifications.severity.${severity.toLowerCase()}`)
}

function openAction(url?: string) {
  if (!url) return
  window.open(url, '_blank', 'noopener')
}
</script>

<style scoped>
.notifications-dashboard {
  @apply max-w-4xl mx-auto px-4 py-6 space-y-6;
}

.dashboard-header {
  @apply flex items-center justify-between;
}

.dashboard-title {
  @apply text-3xl font-bold text-neutral-900;
}

.header-actions {
  @apply flex items-center gap-3;
}

.sse-badge {
  @apply flex items-center gap-2 text-sm px-3 py-1 rounded-full bg-neutral-100 text-neutral-600;
}

.sse-badge.connected {
  @apply bg-success-50 text-success-700;
}

.sse-badge .dot {
  @apply w-2 h-2 rounded-full bg-neutral-400;
}

.filters-card {
  @apply flex items-center gap-3 bg-white border border-neutral-200 rounded-lg p-4;
}

.notifications-list {
  @apply space-y-4;
}

.notification-card {
  @apply border border-neutral-200 rounded-lg p-4 flex justify-between gap-4 bg-white;
}

.notification-card.unread {
  @apply border-primary-200 bg-primary-50;
}

.notification-card.error {
  @apply border-danger-200;
}

.notification-card.warning {
  @apply border-warning-200;
}

.notification-card.success {
  @apply border-success-200;
}

.notification-card.info {
  @apply border-neutral-200;
}

.eyebrow {
  @apply uppercase text-xs text-neutral-500;
}

.message {
  @apply text-sm text-neutral-700;
}

.timestamp {
  @apply text-xs text-neutral-500 mt-2;
}

.card-actions {
  @apply flex flex-col gap-2;
}

.empty-state {
  @apply text-center text-neutral-500 py-12;
}
</style>
