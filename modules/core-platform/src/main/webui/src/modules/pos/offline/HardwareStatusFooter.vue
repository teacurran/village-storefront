<template>
  <div class="hardware-status-footer">
    <div
      v-for="device in devices"
      :key="device.type"
      class="device-status"
      :class="`status-${device.status}`"
      :title="device.tooltip"
    >
      <i :class="device.icon" class="device-icon"></i>
      <span class="device-label">{{ device.label }}</span>
      <div v-if="device.status === 'connecting'" class="status-pulse"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useOfflineStore } from './offlineStore'

interface HardwareDevice {
  type: 'network' | 'printer' | 'scanner' | 'drawer'
  label: string
  icon: string
  status: 'connected' | 'disconnected' | 'connecting' | 'error'
  tooltip: string
}

const offlineStore = useOfflineStore()

const networkStatus = computed(() => (offlineStore.isOnline ? 'connected' : 'disconnected'))
const printerStatus = ref<'connected' | 'disconnected' | 'connecting' | 'error'>('disconnected')
const scannerStatus = ref<'connected' | 'disconnected' | 'connecting' | 'error'>('disconnected')
const drawerStatus = ref<'connected' | 'disconnected' | 'connecting' | 'error'>('connected')

const devices = computed<HardwareDevice[]>(() => [
  {
    type: 'network',
    label: 'Network',
    icon: networkStatus.value === 'connected' ? 'pi pi-wifi' : 'pi pi-wifi text-slash',
    status: networkStatus.value,
    tooltip:
      networkStatus.value === 'connected'
        ? 'Connected to network'
        : 'Offline - transactions will queue',
  },
  {
    type: 'printer',
    label: 'Printer',
    icon: 'pi pi-print',
    status: printerStatus.value,
    tooltip: getPrinterTooltip(printerStatus.value),
  },
  {
    type: 'scanner',
    label: 'Scanner',
    icon: 'pi pi-qrcode',
    status: scannerStatus.value,
    tooltip: getScannerTooltip(scannerStatus.value),
  },
  {
    type: 'drawer',
    label: 'Drawer',
    icon: 'pi pi-box',
    status: drawerStatus.value,
    tooltip: getDrawerTooltip(drawerStatus.value),
  },
])

function getPrinterTooltip(status: string): string {
  switch (status) {
    case 'connected':
      return 'Printer ready'
    case 'connecting':
      return 'Connecting to printer...'
    case 'error':
      return 'Printer error - check connection'
    default:
      return 'Printer not detected'
  }
}

function getScannerTooltip(status: string): string {
  switch (status) {
    case 'connected':
      return 'Barcode scanner ready'
    case 'connecting':
      return 'Connecting to scanner...'
    case 'error':
      return 'Scanner error - check USB connection'
    default:
      return 'Scanner not detected'
  }
}

function getDrawerTooltip(status: string): string {
  switch (status) {
    case 'connected':
      return 'Cash drawer ready'
    case 'error':
      return 'Drawer error - check connection'
    default:
      return 'Cash drawer not detected'
  }
}

// Simulate hardware detection (replace with actual hardware API calls)
let hardwareCheckInterval: number | null = null

onMounted(() => {
  checkHardware()
  hardwareCheckInterval = window.setInterval(checkHardware, 10000) // Check every 10s
})

onUnmounted(() => {
  if (hardwareCheckInterval) {
    clearInterval(hardwareCheckInterval)
  }
})

async function checkHardware() {
  // TODO: Replace with actual hardware detection logic
  // For now, simulate random connection states

  // Check for USB HID devices (barcode scanners)
  if ('hid' in navigator) {
    try {
      // @ts-ignore - WebHID API might not be in types yet
      const devices = await navigator.hid.getDevices()
      scannerStatus.value = devices.length > 0 ? 'connected' : 'disconnected'
    } catch {
      scannerStatus.value = 'disconnected'
    }
  }

  // Printer and drawer status would come from Stripe Terminal SDK or similar
  // For demo purposes, assume they're available if network is online
  if (offlineStore.isOnline) {
    printerStatus.value = 'connected'
    drawerStatus.value = 'connected'
  } else {
    printerStatus.value = 'disconnected'
    drawerStatus.value = 'disconnected'
  }
}
</script>

<style scoped>
.hardware-status-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2rem;
  padding: 0.75rem 1rem;
  background: var(--surface-50);
  border-top: 1px solid var(--surface-border);
}

.device-status {
  position: relative;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  border-radius: 0.375rem;
  font-size: 0.875rem;
  font-weight: 500;
  transition: all 0.3s ease;
}

.device-icon {
  font-size: 1.125rem;
}

.device-label {
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

/* Status-specific styles */
.device-status.status-connected {
  color: var(--green-700);
  background: var(--green-50);
}

.device-status.status-disconnected {
  color: var(--text-color-secondary);
  background: var(--surface-100);
  opacity: 0.6;
}

.device-status.status-connecting {
  color: var(--blue-700);
  background: var(--blue-50);
}

.device-status.status-error {
  color: var(--red-700);
  background: var(--red-50);
}

/* Pulse animation for connecting state */
.status-pulse {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 100%;
  height: 100%;
  border-radius: 0.375rem;
  background: currentColor;
  opacity: 0;
  animation: pulse-ring 1.5s ease-out infinite;
  pointer-events: none;
}

@keyframes pulse-ring {
  0% {
    opacity: 0.3;
    transform: translate(-50%, -50%) scale(1);
  }
  100% {
    opacity: 0;
    transform: translate(-50%, -50%) scale(1.3);
  }
}

/* Icon with slash for disconnected network */
.text-slash::before {
  content: '\e902'; /* PrimeIcons slash */
  position: absolute;
  color: var(--red-500);
}

@media (max-width: 768px) {
  .hardware-status-footer {
    gap: 1rem;
  }

  .device-label {
    display: none;
  }

  .device-status {
    padding: 0.5rem;
  }
}
</style>
