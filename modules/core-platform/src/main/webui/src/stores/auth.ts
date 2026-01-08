/**
 * Authentication Store
 *
 * Manages user authentication state, JWT tokens, and session lifecycle.
 * Supports impersonation tracking per Section 14 requirements.
 *
 * References:
 * - Architecture Section 4.1: State Management
 * - ADR-001: Tenant Context & Session Management
 */

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { UserProfile, AuthTokens, ImpersonationContext } from '@/api/types'

export const useAuthStore = defineStore('auth', () => {
  // State
  const accessToken = ref<string | null>(null)
  const refreshToken = ref<string | null>(null)
  const tokenExpiresAt = ref<number | null>(null)
  const user = ref<UserProfile | null>(null)
  const impersonationContext = ref<ImpersonationContext | null>(null)

  // Computed
  const isAuthenticated = computed(() => !!accessToken.value && !!user.value)
  const isImpersonating = computed(() => !!impersonationContext.value)
  const userRoles = computed(() => user.value?.roles || [])

  const hasRole = (role: string) => {
    return userRoles.value.includes(role)
  }

  const hasAnyRole = (roles: string[]) => {
    return roles.some((role) => userRoles.value.includes(role))
  }

  // Actions
  async function login(email: string, password: string): Promise<void> {
    // Mock implementation - replace with actual API call
    // const response = await apiClient.post<{ tokens: AuthTokens; user: UserProfile }>(
    //   '/auth/login',
    //   { email, password }
    // )

    // Stub for initial scaffold
    const mockTokens: AuthTokens = {
      accessToken: 'mock-access-token',
      refreshToken: 'mock-refresh-token',
      expiresIn: 900, // 15 minutes
    }

    const mockUser: UserProfile = {
      id: '1',
      email,
      firstName: 'Admin',
      lastName: 'User',
      roles: ['ADMIN', 'STORE_OWNER'],
      tenantId: 'tenant-1',
    }

    setTokens(mockTokens)
    user.value = mockUser

    // Persist to localStorage
    persistAuth()
  }

  async function refreshSession(): Promise<void> {
    if (!refreshToken.value) {
      throw new Error('No refresh token available')
    }

    // Mock implementation - replace with actual API call
    // const response = await apiClient.post<AuthTokens>('/auth/refresh', {
    //   refreshToken: refreshToken.value,
    // })

    const mockTokens: AuthTokens = {
      accessToken: 'new-mock-access-token',
      refreshToken: refreshToken.value,
      expiresIn: 900,
    }

    setTokens(mockTokens)
    persistAuth()
  }

  function logout(): void {
    accessToken.value = null
    refreshToken.value = null
    tokenExpiresAt.value = null
    user.value = null
    impersonationContext.value = null

    // Clear localStorage
    localStorage.removeItem('auth')
  }

  function setTokens(tokens: AuthTokens): void {
    accessToken.value = tokens.accessToken
    refreshToken.value = tokens.refreshToken
    tokenExpiresAt.value = Date.now() + tokens.expiresIn * 1000
  }

  function setImpersonation(context: ImpersonationContext): void {
    impersonationContext.value = context
    persistAuth()
  }

  function clearImpersonation(): void {
    impersonationContext.value = null
    persistAuth()
  }

  function persistAuth(): void {
    const authData = {
      accessToken: accessToken.value,
      refreshToken: refreshToken.value,
      tokenExpiresAt: tokenExpiresAt.value,
      user: user.value,
      impersonationContext: impersonationContext.value,
    }
    localStorage.setItem('auth', JSON.stringify(authData))
  }

  function restoreAuth(): void {
    const stored = localStorage.getItem('auth')
    if (!stored) return

    try {
      const authData = JSON.parse(stored)
      accessToken.value = authData.accessToken
      refreshToken.value = authData.refreshToken
      tokenExpiresAt.value = authData.tokenExpiresAt
      user.value = authData.user
      impersonationContext.value = authData.impersonationContext

      // Check if token is expired
      if (tokenExpiresAt.value && tokenExpiresAt.value < Date.now()) {
        // Attempt refresh
        refreshSession().catch(() => logout())
      }
    } catch (error) {
      console.error('Failed to restore auth state:', error)
      logout()
    }
  }

  return {
    // State
    accessToken,
    refreshToken,
    user,
    impersonationContext,

    // Computed
    isAuthenticated,
    isImpersonating,
    userRoles,

    // Actions
    login,
    refreshSession,
    logout,
    setTokens,
    setImpersonation,
    clearImpersonation,
    hasRole,
    hasAnyRole,
    restoreAuth,
  }
})
