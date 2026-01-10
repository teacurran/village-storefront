<template>
  <div class="settings-view">
    <h1 class="text-2xl font-bold text-neutral-900 mb-6">Settings</h1>

    <!-- Tab Navigation -->
    <div class="bg-white rounded-lg border border-neutral-200 overflow-hidden">
      <div class="border-b border-neutral-200">
        <nav class="-mb-px flex space-x-8 px-6" aria-label="Tabs">
          <button
            v-for="tab in tabs"
            :key="tab.id"
            :class="[
              tab.id === activeTab
                ? 'border-primary-500 text-primary-600'
                : 'border-transparent text-neutral-500 hover:text-neutral-700 hover:border-neutral-300',
              'whitespace-nowrap py-4 px-1 border-b-2 font-medium text-sm'
            ]"
            @click="activeTab = tab.id"
          >
            {{ tab.label }}
          </button>
        </nav>
      </div>

      <!-- Tab Content -->
      <div class="p-6">
        <!-- General Settings Tab -->
        <div v-if="activeTab === 'general'">
          <p class="text-neutral-600">General store settings coming soon...</p>
        </div>

        <!-- OAuth Clients Tab -->
        <div v-if="activeTab === 'oauth-clients'">
          <OAuthClientsTab />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import OAuthClientsTab from '../components/oauth/OAuthClientsTab.vue'

interface Tab {
  id: string
  label: string
}

const tabs: Tab[] = [
  { id: 'general', label: 'General' },
  { id: 'oauth-clients', label: 'OAuth Clients' }
]

const activeTab = ref('general')
</script>
