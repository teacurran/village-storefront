<!--
  Style Guide View

  Displays design tokens, color palettes, typography samples, and accessibility metrics.
  Allows preview of theme configuration before publishing to storefront.

  References:
  - UI/UX Architecture Section 1.9: Design Token Delivery & Governance
  - UI/UX Architecture Section 2.13: Admin Component Inventory
-->

<template>
  <div class="style-guide-view">
    <TokenPreview />
  </div>
</template>

<script setup lang="ts">
import TokenPreview from '@/components/TokenPreview.vue'
</script>

<style scoped>
.style-guide-view {
  @apply w-full;
}
</style>
