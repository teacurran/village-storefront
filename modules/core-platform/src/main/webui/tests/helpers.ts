/**
 * Test Helper Utilities
 *
 * Provides factory functions for creating configured Vue test wrappers
 */

import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import type { Plugin } from 'vue'

/**
 * Returns PrimeVue configuration for test environments
 */
export function getPrimeVuePlugin(): Plugin {
  return {
    install(app) {
      app.use(PrimeVue, {
        ripple: false, // Disable in tests
        inputStyle: 'outlined',
      })
      app.use(ToastService)
    },
  }
}

/**
 * Standard global plugins for component tests
 */
export const getGlobalPlugins = () => {
  return [getPrimeVuePlugin()]
}
