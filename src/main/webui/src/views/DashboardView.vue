<template>
  <div class="dashboard-view">
    <h1 class="text-2xl font-bold text-neutral-900 mb-6">Dashboard</h1>

    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-8">
      <MetricsCard
        title="Total Revenue"
        :value="29584.50"
        format="currency"
        :change="12.5"
        timeframe="Last 30 days"
        :show-sparkline="true"
        :sparkline-data="[100, 120, 115, 134, 168, 132, 200]"
      />
      <MetricsCard
        title="Orders"
        :value="1247"
        :change="8.2"
        timeframe="Last 30 days"
      />
      <MetricsCard
        title="Conversion Rate"
        :value="3.24"
        format="percentage"
        :change="-2.1"
        timeframe="Last 30 days"
      />
      <MetricsCard
        title="Avg Order Value"
        :value="87.42"
        format="currency"
        :change="5.8"
        timeframe="Last 30 days"
      />
    </div>

    <InlineAlert
      tone="info"
      title="Welcome to Village Storefront Admin"
      description="This is a scaffold for the admin SPA. Use the navigation to explore different sections."
      :dismissible="true"
    />
  </div>
</template>

<script setup lang="ts">
import MetricsCard from '@/components/base/MetricsCard.vue'
import InlineAlert from '@/components/base/InlineAlert.vue'
</script>

<style scoped>
.dashboard-view {
  @apply max-w-7xl mx-auto;
}
</style>
