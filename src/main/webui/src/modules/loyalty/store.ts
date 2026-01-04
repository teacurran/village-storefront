/**
 * Loyalty Store
 *
 * Manages loyalty program configuration, member lookups,
 * and point adjustments.
 */

import { defineStore } from 'pinia'
import { ref } from 'vue'
import { emitTelemetryEvent } from '@/telemetry'
import * as loyaltyApi from './api'

export interface LoyaltyTierConfig {
  name: string
  minPoints: number
  multiplier: number
}

export const useLoyaltyStore = defineStore('loyalty', () => {
  const program = ref<loyaltyApi.LoyaltyProgramResponse | null>(null)
  const tiers = ref<LoyaltyTierConfig[]>([])
  const member = ref<loyaltyApi.LoyaltyMemberResponse | null>(null)
  const transactions = ref<loyaltyApi.LoyaltyTransactionResponse[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const sseConnected = ref(false)
  const sseSource = ref<EventSource | null>(null)

  async function loadProgram() {
    loading.value = true
    error.value = null
    try {
      program.value = await loyaltyApi.getProgram()
      tiers.value = parseTiers(program.value?.tierConfig)
      emitTelemetryEvent('view_loyalty_program', { programId: program.value?.id })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load program'
    } finally {
      loading.value = false
    }
  }

  function parseTiers(raw?: string) {
    if (!raw) return []
    try {
      const parsed = JSON.parse(raw)
      return parsed.map((tier: any) => ({
        name: tier.name,
        minPoints: tier.minPoints,
        multiplier: Number(tier.multiplier),
      }))
    } catch (error) {
      console.warn('Failed to parse tier config', error)
      return []
    }
  }

  async function lookupMember(userId: string) {
    loading.value = true
    error.value = null
    try {
      member.value = await loyaltyApi.getMember(userId)
      transactions.value = await loyaltyApi.getTransactions(userId)
      emitTelemetryEvent('view_loyalty_member', { userId })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load member'
      member.value = null
      transactions.value = []
      throw err
    } finally {
      loading.value = false
    }
  }

  async function adjustPoints(points: number, reason: string) {
    if (!member.value) return
    await loyaltyApi.adjustPoints(member.value.userId, points, reason)
    await lookupMember(member.value.userId)
    emitTelemetryEvent('action_loyalty_adjust', {
      userId: member.value.userId,
      points,
      reason,
    })
  }

  function connectSSE() {
    if (sseSource.value) return
    try {
      sseSource.value = loyaltyApi.connectLoyaltySSE(() => {
        if (member.value) {
          lookupMember(member.value.userId).catch(() => null)
        }
      })
      sseConnected.value = true
    } catch (err) {
      console.error('Failed to connect loyalty SSE', err)
    }
  }

  function disconnectSSE() {
    sseSource.value?.close()
    sseSource.value = null
    sseConnected.value = false
  }

  return {
    program,
    tiers,
    member,
    transactions,
    loading,
    error,
    sseConnected,
    loadProgram,
    lookupMember,
    adjustPoints,
    connectSSE,
    disconnectSSE,
  }
})
