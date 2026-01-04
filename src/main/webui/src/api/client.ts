/**
 * API Client for Village Storefront Admin
 *
 * Wraps generated OpenAPI client with tenant context, authentication,
 * and error handling. All requests automatically include tenant resolution
 * via Host header and JWT tokens when available.
 *
 * References:
 * - OpenAPI Spec: api/v1/openapi.yaml
 * - Architecture Section 5: API Contracts
 */

import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios'
import { useAuthStore } from '@/stores/auth'
import { useTenantStore } from '@/stores/tenant'

export interface ApiClientConfig {
  baseURL?: string
  timeout?: number
}

export class ApiClient {
  private client: AxiosInstance

  constructor(config: ApiClientConfig = {}) {
    this.client = axios.create({
      baseURL: config.baseURL || '/api/v1',
      timeout: config.timeout || 30000,
      headers: {
        'Content-Type': 'application/json',
      },
    })

    this.setupInterceptors()
  }

  private setupInterceptors() {
    // Request interceptor: inject auth token and tenant context
    this.client.interceptors.request.use(
      (config) => {
        const authStore = useAuthStore()
        const tenantStore = useTenantStore()

        // Add JWT token if available
        if (authStore.accessToken) {
          config.headers.Authorization = `Bearer ${authStore.accessToken}`
        }

        // Add tenant context headers (for impersonation tracking)
        if (tenantStore.currentTenant) {
          config.headers['X-Tenant-ID'] = tenantStore.currentTenant.id
        }

        if (authStore.impersonationContext) {
          config.headers['X-Impersonation-Context'] = JSON.stringify(
            authStore.impersonationContext
          )
        }

        return config
      },
      (error) => Promise.reject(error)
    )

    // Response interceptor: handle token refresh and errors
    this.client.interceptors.response.use(
      (response) => response,
      async (error) => {
        const authStore = useAuthStore()
        const originalRequest = error.config

        // Handle 401 Unauthorized - attempt token refresh
        if (error.response?.status === 401 && !originalRequest._retry) {
          originalRequest._retry = true

          try {
            await authStore.refreshSession()
            // Retry original request with new token
            return this.client(originalRequest)
          } catch (refreshError) {
            // Refresh failed - redirect to login
            authStore.logout()
            return Promise.reject(refreshError)
          }
        }

        // Handle 403 Forbidden - RBAC violation
        if (error.response?.status === 403) {
          console.error('Permission denied:', error.response.data)
        }

        // Handle 404 Not Found
        if (error.response?.status === 404) {
          console.warn('Resource not found:', error.config.url)
        }

        // Handle 500+ Server Errors
        if (error.response?.status >= 500) {
          console.error('Server error:', error.response.data)
        }

        return Promise.reject(error)
      }
    )
  }

  /**
   * Execute a GET request
   */
  async get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    const response = await this.client.get<T>(url, config)
    return response.data
  }

  /**
   * Execute a POST request
   */
  async post<T>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
    const response = await this.client.post<T>(url, data, config)
    return response.data
  }

  /**
   * Execute a PUT request
   */
  async put<T>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
    const response = await this.client.put<T>(url, data, config)
    return response.data
  }

  /**
   * Execute a PATCH request
   */
  async patch<T>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
    const response = await this.client.patch<T>(url, data, config)
    return response.data
  }

  /**
   * Execute a DELETE request
   */
  async delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    const response = await this.client.delete<T>(url, config)
    return response.data
  }

  /**
   * Get the underlying Axios instance for advanced use cases
   */
  getClient(): AxiosInstance {
    return this.client
  }
}

// Singleton instance
export const apiClient = new ApiClient()

export * from './generated'
