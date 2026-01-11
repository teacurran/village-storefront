<!--
  TokenPreview Component

  Displays design tokens with contrast verification for accessibility.
  Used in style guide page to preview theme configuration.

  References:
  - UI/UX Architecture Section 1.9: Design Token Delivery & Governance
  - WCAG 2.1 Level AA contrast requirements (4.5:1 for normal text, 3:1 for large text)
-->

<template>
  <div class="token-preview">
    <div class="preview-header">
      <h2 class="text-2xl font-bold text-neutral-900">Design Tokens</h2>
      <div v-if="tokenVersion" class="token-version">
        <span class="text-sm text-neutral-600">Version:</span>
        <code class="text-sm font-mono bg-neutral-100 px-2 py-1 rounded">{{ tokenVersion }}</code>
      </div>
    </div>

    <!-- Color Tokens Section -->
    <section class="token-section">
      <h3 class="section-title">Color Palette</h3>
      <p class="section-description">
        Primary and secondary color scales with contrast verification
      </p>

      <div class="color-grid">
        <!-- Primary Colors -->
        <div class="color-scale">
          <h4 class="scale-title">Primary</h4>
          <div class="color-row">
            <div
              v-for="shade in colorShades"
              :key="`primary-${shade}`"
              class="color-swatch"
            >
              <div
                class="swatch-box"
                :style="{
                  backgroundColor: getCSSVariable(`--color-primary-${shade}`),
                }"
              >
                <div class="contrast-indicators">
                  <ContrastBadge
                    :ratio="calculateContrast(`--color-primary-${shade}`, '--color-neutral-950')"
                    size="sm"
                  />
                </div>
              </div>
              <div class="swatch-meta">
                <code class="swatch-code">{{ shade }}</code>
                <code class="swatch-hex">{{ getCSSVariable(`--color-primary-${shade}`) }}</code>
              </div>
            </div>
          </div>
        </div>

        <!-- Secondary Colors -->
        <div class="color-scale">
          <h4 class="scale-title">Secondary</h4>
          <div class="color-row">
            <div
              v-for="shade in colorShades"
              :key="`secondary-${shade}`"
              class="color-swatch"
            >
              <div
                class="swatch-box"
                :style="{
                  backgroundColor: getCSSVariable(`--color-secondary-${shade}`),
                }"
              >
                <div class="contrast-indicators">
                  <ContrastBadge
                    :ratio="calculateContrast(`--color-secondary-${shade}`, '--color-neutral-950')"
                    size="sm"
                  />
                </div>
              </div>
              <div class="swatch-meta">
                <code class="swatch-code">{{ shade }}</code>
                <code class="swatch-hex">{{ getCSSVariable(`--color-secondary-${shade}`) }}</code>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Semantic Colors -->
      <div class="semantic-colors">
        <h4 class="scale-title">Semantic Colors</h4>
        <div class="color-row">
          <div
            v-for="color in semanticColors"
            :key="color"
            class="color-swatch"
          >
            <div
              class="swatch-box swatch-box-large"
              :style="{
                backgroundColor: getCSSVariable(`--color-${color}-500`),
              }"
            >
              <div class="contrast-indicators">
                <ContrastBadge
                  :ratio="calculateContrast(`--color-${color}-500`, '--color-neutral-50')"
                  label="AA"
                />
              </div>
            </div>
            <div class="swatch-meta">
              <code class="swatch-code text-capitalize">{{ color }}</code>
              <code class="swatch-hex">{{ getCSSVariable(`--color-${color}-500`) }}</code>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Typography Tokens Section -->
    <section class="token-section">
      <h3 class="section-title">Typography</h3>
      <p class="section-description">
        Font families and type scale with contrast verification
      </p>

      <div class="typography-samples">
        <div class="sample-item">
          <label class="sample-label">Sans Serif (Body)</label>
          <p
            class="sample-text text-2xl"
            :style="{ fontFamily: getCSSVariable('--font-sans', 'Inter') }"
          >
            The quick brown fox jumps over the lazy dog
          </p>
          <code class="sample-code">{{ getCSSVariable('--font-sans', 'Inter') }}</code>
        </div>

        <div class="sample-item">
          <label class="sample-label">Serif (Headings)</label>
          <p
            class="sample-text text-2xl"
            :style="{ fontFamily: getCSSVariable('--font-serif', 'Georgia') }"
          >
            The quick brown fox jumps over the lazy dog
          </p>
          <code class="sample-code">{{ getCSSVariable('--font-serif', 'Georgia') }}</code>
        </div>

        <div class="sample-item">
          <label class="sample-label">Monospace (Code)</label>
          <p
            class="sample-text text-lg"
            :style="{ fontFamily: getCSSVariable('--font-mono', 'Menlo') }"
          >
            const greeting = "Hello, World!";
          </p>
          <code class="sample-code">{{ getCSSVariable('--font-mono', 'Menlo') }}</code>
        </div>
      </div>
    </section>

    <!-- Contrast Summary -->
    <section class="token-section">
      <h3 class="section-title">Accessibility Summary</h3>
      <div class="contrast-summary">
        <InlineAlert
          v-if="failedContrastCount > 0"
          tone="warning"
          :title="`${failedContrastCount} contrast issue${failedContrastCount === 1 ? '' : 's'} detected`"
          :description="`Some color combinations do not meet WCAG 2.1 Level AA requirements (4.5:1 for normal text). Review the badges above for details.`"
        />
        <InlineAlert
          v-else
          tone="success"
          title="All contrast checks passed"
          description="All color combinations meet WCAG 2.1 Level AA accessibility requirements."
        />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useTenantStore } from '@/stores/tenant'
import InlineAlert from '@/components/base/InlineAlert.vue'
import ContrastBadge from './ContrastBadge.vue'

const tenantStore = useTenantStore()

const tokenVersion = computed(() => tenantStore.tokenVersion)

const colorShades = [50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950]
const semanticColors = ['success', 'warning', 'error', 'neutral']

// Track failed contrast checks
const failedContrastCount = computed(() => {
  let count = 0

  // Check primary colors against dark background
  colorShades.forEach((shade) => {
    if (calculateContrast(`--color-primary-${shade}`, '--color-neutral-950') < 4.5) {
      count++
    }
    if (calculateContrast(`--color-secondary-${shade}`, '--color-neutral-950') < 4.5) {
      count++
    }
  })

  // Check semantic colors against light background
  semanticColors.forEach((color) => {
    if (calculateContrast(`--color-${color}-500`, '--color-neutral-50') < 4.5) {
      count++
    }
  })

  return count
})

/**
 * Get a CSS variable value from the document root
 */
function getCSSVariable(name: string, fallback = '#000000'): string {
  if (typeof window === 'undefined' || !window.document?.documentElement) {
    return fallback
  }

  const value = getComputedStyle(window.document.documentElement).getPropertyValue(name).trim()
  if (!value) {
    return fallback
  }

  if (value.startsWith('rgb')) {
    return rgbToHex(value)
  }

  if (/^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(value)) {
    return value
  }

  return fallback
}

/**
 * Calculate contrast ratio between two colors
 * Uses WCAG 2.1 relative luminance formula
 */
function calculateContrast(color1Var: string, color2Var: string): number {
  const color1 = getCSSVariable(color1Var)
  const color2 = getCSSVariable(color2Var)

  const lum1 = getRelativeLuminance(color1)
  const lum2 = getRelativeLuminance(color2)

  const lighter = Math.max(lum1, lum2)
  const darker = Math.min(lum1, lum2)

  return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Calculate relative luminance for a color
 * @see https://www.w3.org/TR/WCAG21/#dfn-relative-luminance
 */
function getRelativeLuminance(hexColor: string): number {
  const hex = normalizeHex(hexColor)
  if (!hex) {
    return 0
  }

  const r = parseInt(hex.substring(0, 2), 16) / 255
  const g = parseInt(hex.substring(2, 4), 16) / 255
  const b = parseInt(hex.substring(4, 6), 16) / 255

  // Apply gamma correction
  const rsRGB = r <= 0.03928 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4)
  const gsRGB = g <= 0.03928 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4)
  const bsRGB = b <= 0.03928 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4)

  // Calculate relative luminance
  return 0.2126 * rsRGB + 0.7152 * gsRGB + 0.0722 * bsRGB
}

function rgbToHex(rgbValue: string): string {
  const match = rgbValue.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/i)
  if (!match) {
    return '#000000'
  }
  const [, r, g, b] = match.map(Number)

  const toHex = (component: number) => {
    const value = Math.max(0, Math.min(255, component))
    return value.toString(16).padStart(2, '0')
  }

  return `#${toHex(r)}${toHex(g)}${toHex(b)}`
}

function normalizeHex(color: string): string | null {
  const sanitized = color.startsWith('#') ? color.slice(1) : color
  if (sanitized.length === 3) {
    return sanitized
      .split('')
      .map((c) => c + c)
      .join('')
  }
  if (/^[0-9a-fA-F]{6}$/.test(sanitized)) {
    return sanitized
  }
  return null
}
</script>

<style scoped>
.token-preview {
  @apply max-w-7xl mx-auto px-4 py-6 space-y-8;
}

.preview-header {
  @apply flex items-center justify-between mb-6;
}

.token-version {
  @apply flex items-center gap-2;
}

.token-section {
  @apply bg-white border border-neutral-200 rounded-lg p-6 shadow-sm;
}

.section-title {
  @apply text-xl font-bold text-neutral-900 mb-2;
}

.section-description {
  @apply text-sm text-neutral-600 mb-6;
}

.color-grid {
  @apply space-y-8;
}

.color-scale {
  @apply space-y-4;
}

.scale-title {
  @apply text-base font-semibold text-neutral-900;
}

.color-row {
  @apply grid grid-cols-11 gap-2;
}

.semantic-colors {
  @apply mt-8;
}

.semantic-colors .color-row {
  @apply grid grid-cols-4 gap-4;
}

.color-swatch {
  @apply flex flex-col;
}

.swatch-box {
  @apply relative w-full h-16 rounded-lg border border-neutral-200 shadow-xs overflow-hidden;
  @apply transition-transform hover:scale-105 cursor-pointer;
}

.swatch-box-large {
  @apply h-24;
}

.contrast-indicators {
  @apply absolute bottom-1 right-1;
}

.swatch-meta {
  @apply mt-2 space-y-1 text-center;
}

.swatch-code {
  @apply block text-xs font-mono text-neutral-600;
}

.swatch-hex {
  @apply block text-xs font-mono text-neutral-500;
}

.typography-samples {
  @apply space-y-6;
}

.sample-item {
  @apply p-4 bg-neutral-50 rounded-lg border border-neutral-200;
}

.sample-label {
  @apply block text-sm font-semibold text-neutral-700 mb-2;
}

.sample-text {
  @apply text-neutral-900 mb-2;
}

.sample-code {
  @apply text-xs font-mono text-neutral-500;
}

.contrast-summary {
  @apply mt-4;
}

.text-capitalize {
  @apply capitalize;
}
</style>
