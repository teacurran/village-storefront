/**
 * Theme Composable
 *
 * Manages design token loading, application, and synchronization with backend.
 * Provides utilities for runtime theme updates and CSS variable management.
 *
 * References:
 * - UI/UX Architecture Section 1.9: Design Token Delivery & Governance
 * - UI/UX Architecture Section 4.1: State Management
 */

import { ref, computed, watch } from 'vue'
import { useTenantStore } from '@/stores/tenant'
import type { DesignTokens } from '@/api/types'

export interface ThemeColors {
  primary?: Record<string, string>
  secondary?: Record<string, string>
  success?: Record<string, string>
  warning?: Record<string, string>
  error?: Record<string, string>
  neutral?: Record<string, string>
}

export interface ThemeTypography {
  fontFamily: {
    sans?: string
    serif?: string
    mono?: string
  }
  fontSize: Record<string, string>
  fontWeight: Record<string, number>
  lineHeight: Record<string, string>
}

export interface ThemeSpacing {
  scale: Record<string, string>
}

export interface ThemeShadows {
  xs?: string
  sm?: string
  md?: string
  lg?: string
  xl?: string
}

export interface Theme {
  colors?: ThemeColors
  typography?: ThemeTypography
  spacing?: ThemeSpacing
  shadows?: ThemeShadows
  borderRadius?: Record<string, string>
}

const isLoading = ref(false)
const isApplying = ref(false)

export function useTheme() {
  const tenantStore = useTenantStore()

  const currentTheme = computed<Theme | null>(() => {
    if (!tenantStore.designTokens) return null

    // Transform backend token format to internal theme structure
    return transformTokensToTheme(tenantStore.designTokens)
  })

  const tokenVersion = computed(() => tenantStore.tokenVersion)

  /**
   * Load theme tokens from backend API
   */
  async function loadTheme(): Promise<void> {
    if (isLoading.value) return

    isLoading.value = true
    try {
      await tenantStore.loadDesignTokens()
    } catch (error) {
      console.error('Failed to load theme tokens:', error)
      throw error
    } finally {
      isLoading.value = false
    }
  }

  /**
   * Apply theme tokens to document as CSS variables
   */
  function applyTheme(theme: Theme): void {
    if (isApplying.value) return

    isApplying.value = true
    const root = document.documentElement

    try {
      // Apply color tokens
      if (theme.colors) {
        applyColorTokens(root, theme.colors)
      }

      // Apply typography tokens
      if (theme.typography) {
        applyTypographyTokens(root, theme.typography)
      }

      // Apply spacing tokens
      if (theme.spacing) {
        applySpacingTokens(root, theme.spacing)
      }

      // Apply shadow tokens
      if (theme.shadows) {
        applyShadowTokens(root, theme.shadows)
      }

      // Apply border radius tokens
      if (theme.borderRadius) {
        applyBorderRadiusTokens(root, theme.borderRadius)
      }
    } finally {
      isApplying.value = false
    }
  }

  /**
   * Preview theme changes without persisting
   */
  function previewTheme(theme: Theme): void {
    applyTheme(theme)
  }

  /**
   * Reset theme to default/stored values
   */
  async function resetTheme(): Promise<void> {
    await loadTheme()
  }

  // Watch for tenant store token changes and auto-apply
  watch(
    () => tenantStore.designTokens,
    (tokens) => {
      if (tokens) {
        const theme = transformTokensToTheme(tokens)
        applyTheme(theme)
      }
    },
    { deep: true }
  )

  return {
    currentTheme,
    tokenVersion,
    isLoading,
    isApplying,
    loadTheme,
    applyTheme,
    previewTheme,
    resetTheme,
  }
}

/**
 * Transform backend design tokens to internal theme structure
 */
function transformTokensToTheme(tokens: DesignTokens): Theme {
  return {
    colors: {
      primary: tokens.colors?.primary,
      secondary: tokens.colors?.secondary,
      success: tokens.colors?.success,
      warning: tokens.colors?.warning,
      error: tokens.colors?.error,
      neutral: tokens.colors?.neutral,
    },
    typography: {
      fontFamily: tokens.typography?.fontFamily || {},
      fontSize: tokens.typography?.fontSize || {},
      fontWeight: tokens.typography?.fontWeight || {},
      lineHeight: tokens.typography?.lineHeight || {},
    },
    spacing: tokens.spacing,
    shadows: tokens.shadows,
    borderRadius: tokens.borderRadius,
  }
}

/**
 * Apply color tokens as CSS variables
 */
function applyColorTokens(root: HTMLElement, colors: ThemeColors): void {
  applyColorScale(root, colors.primary, 'primary')
  applyColorScale(root, colors.secondary, 'secondary')

  const semanticColors = ['success', 'warning', 'error', 'neutral'] as const
  semanticColors.forEach((colorName) => {
    applyColorScale(root, colors[colorName], colorName)
  })
}

function applyColorScale(
  root: HTMLElement,
  scale: Record<string, string> | undefined,
  name: string
): void {
  if (!scale) return
  Object.entries(scale).forEach(([shade, value]) => {
    root.style.setProperty(`--color-${name}-${shade}`, value)
  })
}

/**
 * Apply typography tokens as CSS variables
 */
function applyTypographyTokens(root: HTMLElement, typography: ThemeTypography): void {
  // Font families
  if (typography.fontFamily) {
    Object.entries(typography.fontFamily).forEach(([name, value]) => {
      root.style.setProperty(`--font-${name}`, value)
    })
  }

  // Font sizes
  if (typography.fontSize) {
    Object.entries(typography.fontSize).forEach(([name, value]) => {
      root.style.setProperty(`--text-${name}`, value)
    })
  }

  // Font weights
  if (typography.fontWeight) {
    Object.entries(typography.fontWeight).forEach(([name, value]) => {
      root.style.setProperty(`--font-weight-${name}`, String(value))
    })
  }

  // Line heights
  if (typography.lineHeight) {
    Object.entries(typography.lineHeight).forEach(([name, value]) => {
      root.style.setProperty(`--leading-${name}`, value)
    })
  }
}

/**
 * Apply spacing tokens as CSS variables
 */
function applySpacingTokens(root: HTMLElement, spacing: ThemeSpacing): void {
  if (spacing.scale) {
    Object.entries(spacing.scale).forEach(([name, value]) => {
      root.style.setProperty(`--spacing-${name}`, value)
    })
  }
}

/**
 * Apply shadow tokens as CSS variables
 */
function applyShadowTokens(root: HTMLElement, shadows: ThemeShadows): void {
  Object.entries(shadows).forEach(([name, value]) => {
    root.style.setProperty(`--shadow-${name}`, value)
  })
}

/**
 * Apply border radius tokens as CSS variables
 */
function applyBorderRadiusTokens(root: HTMLElement, borderRadius: Record<string, string>): void {
  Object.entries(borderRadius).forEach(([name, value]) => {
    root.style.setProperty(`--radius-${name}`, value)
  })
}
