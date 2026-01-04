<template>
  <Teleport to="body">
    <Transition name="fade">
      <div v-if="isOpen" class="command-palette-overlay" @click="close">
        <div class="command-palette" @click.stop>
          <div class="command-palette-header">
            <input
              ref="searchInput"
              v-model="query"
              type="text"
              placeholder="Type a command or search..."
              class="command-palette-input"
              @keydown.down.prevent="selectNext"
              @keydown.up.prevent="selectPrevious"
              @keydown.enter.prevent="executeSelected"
              @keydown.esc="close"
            />
          </div>

          <div v-if="filteredResults.length" class="command-palette-results">
            <div
              v-for="(result, index) in filteredResults"
              :key="result.id"
              :class="['command-palette-item', { active: index === selectedIndex }]"
              @click="execute(result)"
              @mouseenter="selectedIndex = index"
            >
              <span class="command-icon">{{ result.icon }}</span>
              <div class="flex-1">
                <div class="command-label">{{ result.label }}</div>
                <div v-if="result.description" class="command-description">
                  {{ result.description }}
                </div>
              </div>
              <kbd v-if="result.shortcut" class="command-shortcut">{{ result.shortcut }}</kbd>
            </div>
          </div>

          <div v-else class="command-palette-empty">
            <p class="text-neutral-500 text-sm">No results found</p>
          </div>

          <div class="command-palette-footer">
            <div class="text-xs text-neutral-500 flex items-center gap-4">
              <span><kbd>↑</kbd><kbd>↓</kbd> to navigate</span>
              <span><kbd>↵</kbd> to select</span>
              <span><kbd>esc</kbd> to close</span>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { emitTelemetryEvent } from '@/telemetry'

export interface CommandItem {
  id: string
  label: string
  description?: string
  icon: string
  shortcut?: string
  action: () => void
  keywords?: string[]
}

const props = withDefaults(
  defineProps<{
    isOpen: boolean
  }>(),
  {
    isOpen: false,
  }
)

const emit = defineEmits<{
  close: []
  open: []
}>()

const router = useRouter()
const query = ref('')
const selectedIndex = ref(0)
const searchInput = ref<HTMLInputElement | null>(null)

// Command items
const commands = ref<CommandItem[]>([
  {
    id: 'nav-dashboard',
    label: 'Go to Dashboard',
    icon: '📊',
    action: () => router.push('/'),
    keywords: ['dashboard', 'home', 'overview'],
  },
  {
    id: 'nav-catalog',
    label: 'Go to Catalog',
    icon: '📦',
    action: () => router.push('/catalog'),
    keywords: ['catalog', 'products', 'inventory'],
  },
  {
    id: 'nav-pos',
    label: 'Go to Point of Sale',
    icon: '🛒',
    action: () => router.push('/pos'),
    keywords: ['pos', 'point of sale', 'checkout'],
  },
  {
    id: 'nav-settings',
    label: 'Go to Settings',
    icon: '⚙️',
    shortcut: '⌘,',
    action: () => router.push('/settings'),
    keywords: ['settings', 'preferences', 'configuration'],
  },
  {
    id: 'action-new-product',
    label: 'Create New Product',
    description: 'Add a new product to your catalog',
    icon: '➕',
    action: () => {
      router.push('/catalog/new')
    },
    keywords: ['new', 'create', 'product', 'add'],
  },
])

const filteredResults = computed(() => {
  if (!query.value) {
    return commands.value
  }

  const searchTerm = query.value.toLowerCase()
  return commands.value.filter((cmd) => {
    const matchLabel = cmd.label.toLowerCase().includes(searchTerm)
    const matchDescription = cmd.description?.toLowerCase().includes(searchTerm)
    const matchKeywords = cmd.keywords?.some((kw) => kw.includes(searchTerm))

    return matchLabel || matchDescription || matchKeywords
  })
})

function selectNext() {
  selectedIndex.value = (selectedIndex.value + 1) % filteredResults.value.length
}

function selectPrevious() {
  selectedIndex.value =
    selectedIndex.value === 0 ? filteredResults.value.length - 1 : selectedIndex.value - 1
}

function executeSelected() {
  const selected = filteredResults.value[selectedIndex.value]
  if (selected) {
    execute(selected)
  }
}

function execute(command: CommandItem) {
  command.action()
  emitTelemetryEvent('command-palette:execute', {
    commandId: command.id,
    destination: router.currentRoute.value.fullPath,
    label: command.label,
  })
  close()
}

function close() {
  emit('close')
  query.value = ''
  selectedIndex.value = 0
}

// Keyboard shortcut handler
function handleKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key === 'k') {
    event.preventDefault()
    if (props.isOpen) {
      close()
    } else {
      emit('open')
    }
  }
}

// Focus input when opened
watch(
  () => props.isOpen,
  (isOpen) => {
    if (isOpen) {
      setTimeout(() => {
        searchInput.value?.focus()
      }, 100)
    }
  }
)

onMounted(() => {
  document.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  document.removeEventListener('keydown', handleKeydown)
})
</script>

<style scoped>
.command-palette-overlay {
  @apply fixed inset-0 bg-black bg-opacity-50 flex items-start justify-center pt-24 px-4 z-50;
}

.command-palette {
  @apply bg-white rounded-lg shadow-strong w-full max-w-2xl overflow-hidden;
}

.command-palette-header {
  @apply p-4 border-b border-neutral-200;
}

.command-palette-input {
  @apply w-full text-lg outline-none;
}

.command-palette-results {
  @apply max-h-96 overflow-y-auto;
}

.command-palette-item {
  @apply flex items-center gap-3 px-4 py-3 cursor-pointer border-b border-neutral-100 transition-colors;
}

.command-palette-item:hover,
.command-palette-item.active {
  @apply bg-primary-50;
}

.command-icon {
  @apply text-2xl flex-shrink-0;
}

.command-label {
  @apply font-medium text-neutral-900;
}

.command-description {
  @apply text-sm text-neutral-600;
}

.command-shortcut {
  @apply px-2 py-1 text-xs font-mono bg-neutral-100 border border-neutral-300 rounded;
}

.command-palette-empty {
  @apply p-8 text-center;
}

.command-palette-footer {
  @apply p-3 bg-neutral-50 border-t border-neutral-200;
}

kbd {
  @apply inline-block px-1.5 py-0.5 text-xs font-mono bg-neutral-100 border border-neutral-300 rounded;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
