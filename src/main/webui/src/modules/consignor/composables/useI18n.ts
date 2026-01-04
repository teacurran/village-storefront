/**
 * Lightweight i18n composable for consignor portal
 *
 * Provides translation function with support for English and Spanish locales.
 * Uses shared locale files with fallback to English.
 *
 * References:
 * - Task I3.T7: Localization requirement
 * - Architecture Section 4.6: Localization & Multi-Currency Display
 */

import { ref, computed } from 'vue'
import type { Money } from '@/api/types'

type LocaleKey = string
type TranslationParams = Record<string, string | number>

// Global locale state - could be moved to Pinia store if needed
const currentLocale = ref<'en' | 'es'>('en')
const currentTimeZone = ref<string>('UTC')

// Translation dictionaries loaded from locale files
const translations = ref<Record<string, any>>({
  en: {},
  es: {},
})

const localeTagMap: Record<'en' | 'es', string> = {
  en: 'en-US',
  es: 'es-ES',
}

/**
 * Load translations from locale files
 */
export async function loadTranslations() {
  try {
    const [en, es] = await Promise.all([
      import('@/locales/en.json'),
      import('@/locales/es.json'),
    ])

    translations.value = {
      en: en.default || en,
      es: es.default || es,
    }
  } catch (error) {
    console.error('Failed to load translations:', error)
    // Fallback to empty object if locale files don't exist yet
    translations.value = {
      en: {},
      es: {},
    }
  }
}

function resolveTranslation(dictionary: Record<string, any> | undefined, key: string) {
  if (!dictionary) {
    return undefined
  }

  return key.split('.').reduce<any>((acc, segment) => {
    if (acc && typeof acc === 'object' && segment in acc) {
      return acc[segment]
    }
    return undefined
  }, dictionary)
}

/**
 * Composable for accessing translations
 */
export function useI18n() {
  const locale = computed(() => currentLocale.value)
  const timeZone = computed(() => currentTimeZone.value)

  function getLocaleTag(): string {
    return localeTagMap[currentLocale.value] || 'en-US'
  }

  function t(key: LocaleKey, params?: TranslationParams): string {
    const dict = translations.value[locale.value] || translations.value.en || {}
    let translation =
      resolveTranslation(dict, key) ??
      resolveTranslation(translations.value.en, key) ??
      key

    // Replace parameters in translation
    if (params) {
      Object.entries(params).forEach(([paramKey, paramValue]) => {
        translation = translation.replace(`{${paramKey}}`, String(paramValue))
      })
    }

    return translation
  }

  function setLocale(newLocale: 'en' | 'es') {
    currentLocale.value = newLocale
  }

  function setTimeZone(newTimeZone: string) {
    currentTimeZone.value = newTimeZone || 'UTC'
  }

  function formatCurrency(
    money: Money,
    options?: Intl.NumberFormatOptions
  ): string {
    return new Intl.NumberFormat(getLocaleTag(), {
      style: 'currency',
      currency: money.currency,
      ...options,
    }).format(money.amount / 100)
  }

  function formatDate(
    dateInput: string | Date,
    options?: Intl.DateTimeFormatOptions
  ): string {
    const date =
      typeof dateInput === 'string' ? new Date(dateInput) : dateInput
    return new Intl.DateTimeFormat(getLocaleTag(), {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
      timeZone: currentTimeZone.value,
      ...options,
    }).format(date)
  }

  function formatRelativeTime(dateInput: string | Date): string {
    const date =
      typeof dateInput === 'string' ? new Date(dateInput) : dateInput
    const now = new Date()
    const diffMs = now.getTime() - date.getTime()
    const diffMins = Math.floor(diffMs / 60000)
    const diffHours = Math.floor(diffMs / 3600000)
    const diffDays = Math.floor(diffMs / 86400000)

    if (diffMins < 1) return t('common.time.justNow')
    if (diffMins < 60)
      return t('common.time.minutesAgo', { count: diffMins })
    if (diffHours < 24)
      return t('common.time.hoursAgo', { count: diffHours })
    if (diffDays < 7) return t('common.time.daysAgo', { count: diffDays })

    return formatDate(date, { month: 'short', day: 'numeric' })
  }

  return {
    locale,
    timeZone,
    t,
    setLocale,
    setTimeZone,
    formatCurrency,
    formatDate,
    formatRelativeTime,
  }
}

// Initialize translations on module load
loadTranslations()
