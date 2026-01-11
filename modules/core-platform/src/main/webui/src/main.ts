/**
 * Main entry point for Village Storefront Admin SPA
 *
 * Bootstraps Vue application with router, Pinia stores, PrimeVue, and telemetry.
 */

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Ripple from 'primevue/ripple'
import ToastService from 'primevue/toastservice'
import App from './App.vue'
import router from './router'
import { emitTelemetryEvent } from '@/telemetry'
import { useTenantStore } from '@/stores/tenant'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/useTheme'

import 'primevue/resources/themes/lara-light-blue/theme.css'
import 'primevue/resources/primevue.min.css'
import 'primeicons/primeicons.css'
import '@/assets/main.css'

const appBootStart = performance.now()

// Create app instance
const app = createApp(App)

// Configure Pinia
const pinia = createPinia()
app.use(pinia)

// Configure Router & PrimeVue plugins
app.use(router)
app.use(PrimeVue, {
  ripple: true,
  inputStyle: 'outlined',
})
app.use(ToastService)
app.directive('ripple', Ripple)

// Mount app
app.mount('#app')

// Initialize stores after mount
const authStore = useAuthStore()
const tenantStore = useTenantStore()
const theme = useTheme()

// Restore authentication state
authStore.restoreAuth()
if (!authStore.isAuthenticated) {
  authStore.bootstrapDemoSession()
}

// Load tenant context and design tokens
tenantStore
  .loadTenant()
  .then(() => theme.loadTheme())
  .catch((error) => {
    console.error('Failed to load tenant context or theme:', error)
  })

const loadTimeMs = Math.round(performance.now() - appBootStart)

emitTelemetryEvent('app:hydrated', {
  loadTimeMs,
  version: __APP_VERSION__,
  userAgent: typeof navigator !== 'undefined' ? navigator.userAgent : 'unknown',
})

// Log app version for quick debugging reference
console.log(`Village Storefront Admin v${__APP_VERSION__} (boot: ${loadTimeMs}ms)`)
