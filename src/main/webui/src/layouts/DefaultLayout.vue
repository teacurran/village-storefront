<template>
  <div class="admin-layout">
    <!-- Impersonation Banner -->
    <div v-if="authStore.isImpersonating" class="impersonation-banner">
      <div class="container mx-auto px-4 py-2 flex items-center justify-between">
        <span class="text-sm font-medium">
          Impersonating {{ tenantStore.tenantName }} as {{ authStore.user?.email }}
        </span>
        <BaseButton size="sm" variant="neutral" @click="handleStopImpersonation">
          Stop Impersonation
        </BaseButton>
      </div>
    </div>

    <div class="admin-shell">
      <!-- Sidebar -->
      <aside class="admin-sidebar">
        <div class="sidebar-header">
          <h1 class="text-xl font-bold text-white">{{ tenantStore.tenantName }}</h1>
          <p class="text-sm text-primary-200">{{ tenantStore.tenantPlan }} Plan</p>
        </div>

        <nav class="sidebar-nav">
          <router-link
            v-for="item in navigation"
            :key="item.name"
            :to="item.to"
            class="nav-item"
            active-class="nav-item-active"
          >
            <span class="nav-icon">{{ item.icon }}</span>
            <span>{{ item.label }}</span>
          </router-link>
        </nav>
      </aside>

      <!-- Main Content -->
      <div class="admin-main">
        <!-- Top Bar -->
        <header class="admin-header">
          <div class="flex items-center gap-4">
            <button class="text-neutral-600 hover:text-neutral-900">
              <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M4 6h16M4 12h16M4 18h16"
                />
              </svg>
            </button>
            <div class="flex-1">
              <button
                class="command-palette-trigger"
                @click="emit('toggle-command-palette')"
              >
                <span class="text-neutral-500">Search...</span>
                <kbd class="kbd">⌘K</kbd>
              </button>
            </div>
          </div>

          <div class="flex items-center gap-4">
            <button class="icon-button" title="Notifications">
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9"
                />
              </svg>
            </button>

            <div class="relative">
              <button class="flex items-center gap-2 text-sm">
                <div class="w-8 h-8 rounded-full bg-primary-600 text-white flex items-center justify-center font-medium">
                  {{ userInitials }}
                </div>
                <span class="font-medium">{{ authStore.user?.firstName }}</span>
              </button>
            </div>
          </div>
        </header>

        <!-- Page Content -->
        <main class="admin-content">
          <router-view />
        </main>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useTenantStore } from '@/stores/tenant'
import BaseButton from '@/components/base/BaseButton.vue'
import { emitTelemetryEvent } from '@/telemetry'

const emit = defineEmits<{
  'toggle-command-palette': []
}>()

const authStore = useAuthStore()
const tenantStore = useTenantStore()

const navigation = [
  { name: 'dashboard', label: 'Dashboard', to: '/', icon: '📊' },
  { name: 'catalog', label: 'Catalog', to: '/catalog', icon: '📦' },
  { name: 'pos', label: 'Point of Sale', to: '/pos', icon: '🛒' },
  { name: 'settings', label: 'Settings', to: '/settings', icon: '⚙️' },
]

const userInitials = computed(() => {
  const user = authStore.user
  if (!user) return '??'
  return `${user.firstName[0]}${user.lastName[0]}`.toUpperCase()
})

watch(
  () => authStore.isImpersonating,
  (isImpersonating) => {
    const context = authStore.impersonationContext
    emitTelemetryEvent('impersonation:banner', {
      active: isImpersonating,
      tenantId: tenantStore.tenantId?.value ?? null,
      adminEmail: context?.adminEmail,
    })

    if (isImpersonating && context) {
      console.warn(
        `[Impersonation] ${context.adminEmail} is impersonating ${tenantStore.tenantName.value} (${tenantStore.tenantId?.value})`
      )
    }
  },
  { immediate: true }
)

function handleStopImpersonation() {
  authStore.clearImpersonation()
  // Would typically reload or redirect
}
</script>

<style scoped>
.admin-layout {
  @apply min-h-screen bg-neutral-100;
}

.impersonation-banner {
  @apply bg-warning-500 text-warning-900;
}

.admin-shell {
  @apply flex h-screen;
}

.admin-sidebar {
  @apply w-64 bg-primary-800 text-white flex flex-col;
}

.sidebar-header {
  @apply p-6 border-b border-primary-700;
}

.sidebar-nav {
  @apply flex-1 py-4 space-y-1;
}

.nav-item {
  @apply flex items-center gap-3 px-6 py-3 text-primary-100 hover:bg-primary-700 transition-colors;
}

.nav-item-active {
  @apply bg-primary-700 text-white font-medium;
}

.nav-icon {
  @apply text-xl;
}

.admin-main {
  @apply flex-1 flex flex-col overflow-hidden;
}

.admin-header {
  @apply bg-white border-b border-neutral-200 px-6 py-4 flex items-center justify-between;
}

.command-palette-trigger {
  @apply w-96 flex items-center justify-between px-4 py-2 bg-neutral-100 rounded-md border border-neutral-300 hover:border-neutral-400 transition-colors;
}

.kbd {
  @apply px-2 py-1 text-xs font-mono bg-white border border-neutral-300 rounded;
}

.icon-button {
  @apply p-2 text-neutral-600 hover:text-neutral-900 rounded-md hover:bg-neutral-100;
}

.admin-content {
  @apply flex-1 overflow-y-auto p-6;
}
</style>
