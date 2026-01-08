<template>
  <div id="app">
    <router-view v-slot="{ Component }">
      <component :is="Component" @toggle-command-palette="toggleCommandPalette" />
    </router-view>

    <CommandPalette
      :is-open="commandPaletteOpen"
      @close="setCommandPaletteOpen(false)"
      @open="setCommandPaletteOpen(true)"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useTenantStore } from '@/stores/tenant'
import CommandPalette from '@/components/CommandPalette.vue'
import { emitTelemetryEvent } from '@/telemetry'

const authStore = useAuthStore()
const tenantStore = useTenantStore()
const commandPaletteOpen = ref(false)

function setCommandPaletteOpen(isOpen: boolean) {
  if (commandPaletteOpen.value === isOpen) {
    return
  }

  commandPaletteOpen.value = isOpen

  emitTelemetryEvent('command-palette:toggle', {
    isOpen,
    route: typeof window !== 'undefined' ? window.location.pathname : '',
    tenantId: tenantStore.tenantId?.value ?? null,
    userId: authStore.user?.id ?? null,
    impersonating: authStore.isImpersonating,
  })
}

function toggleCommandPalette() {
  setCommandPaletteOpen(!commandPaletteOpen.value)
}

// Initialize app
onMounted(async () => {
  // Restore auth state from localStorage
  authStore.restoreAuth()

  // Load tenant context if authenticated
  if (authStore.isAuthenticated) {
    await tenantStore.loadTenant()
  }
})

defineExpose({
  commandPaletteOpen,
  toggleCommandPalette,
  setCommandPaletteOpen,
})
</script>
