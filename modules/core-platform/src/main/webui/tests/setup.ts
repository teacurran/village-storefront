/**
 * Global test setup for Vitest
 *
 * Configures mocks for browser APIs not available in JSDOM
 */

// Mock ResizeObserver (used by PrimeVue components)
class ResizeObserverMock {
  observe() {
    return null
  }
  unobserve() {
    return null
  }
  disconnect() {
    return null
  }
}

if (typeof window !== 'undefined') {
  ;(window as unknown as { ResizeObserver: typeof ResizeObserverMock }).ResizeObserver =
    ResizeObserverMock
}
;(globalThis as unknown as { ResizeObserver: typeof ResizeObserverMock }).ResizeObserver =
  ResizeObserverMock

// Mock IntersectionObserver (used by some PrimeVue components)
class IntersectionObserverMock {
  observe() {
    return null
  }
  unobserve() {
    return null
  }
  disconnect() {
    return null
  }
}

if (typeof window !== 'undefined') {
  ;(window as unknown as { IntersectionObserver: typeof IntersectionObserverMock }).IntersectionObserver =
    IntersectionObserverMock
}
;(globalThis as unknown as { IntersectionObserver: typeof IntersectionObserverMock }).IntersectionObserver =
  IntersectionObserverMock
