/**
 * Playwright tests for POS offline workflows.
 *
 * Covers:
 * - Device pairing flow
 * - Barcode/catalog search + cart management
 * - Split tender entry
 * - Offline queuing + replay once connectivity is restored
 * - Retry/backoff messaging for failed uploads
 * - Encrypted queue export
 */

import { test, expect, type Page, type BrowserContext } from '@playwright/test'

const MOCK_DEVICE_RESPONSE = {
  deviceId: 42,
  deviceName: 'Test POS Terminal',
  encryptionKey: 'dGVzdC1lbmNyeXB0aW9uLWtleS0xMjM0',
  encryptionKeyVersion: 1,
  stripeConnectionToken: 'pst_mock_token',
}

const MOCK_PRODUCTS = [
  {
    variantId: 'demo-widget-pro',
    productId: 'demo-1',
    name: 'Widget Pro',
    sku: 'WIDG-PRO',
    barcode: '123456789012',
    price: 29.99,
    inventoryQuantity: 25,
    categoryId: 'widgets',
  },
]

async function registerDefaultRoutes(page: Page) {
  await page.route('**/api/pos/devices/complete-pairing', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_DEVICE_RESPONSE),
    })
  })

  await page.route('**/api/pos/devices/*/terminal/token', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ connectionToken: 'conn_token' }),
    })
  })

  await page.route('**/api/catalog/products**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: MOCK_PRODUCTS }),
    })
  })

  await page.route('**/api/catalog/products/search**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: MOCK_PRODUCTS }),
    })
  })

  await page.route('**/api/pos/offline/upload', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ enqueued: 1, duplicates: 0 }),
    })
  })
}

async function completePairingFlow(page: Page) {
  await page.fill('#pairing-code', 'TEST1234')
  await page.click('button:has-text("Pair Device")')
  await expect(page.getByText('Device Paired')).toBeVisible()
}

async function addWidgetToCart(page: Page) {
  await page.fill('.search-input', 'Widget')
  await page.waitForTimeout(400)
  await page.click('.search-result-item:first-child')
  await expect(page.locator('.cart-item')).toHaveCount(1)
}

async function recordCashPayment(page: Page, amount?: number) {
  if (amount) {
    await page.fill('#payment-amount', amount.toFixed(2))
  } else {
    const dueText = await page.locator('[data-test="amount-due"]').textContent()
    const due = Number.parseFloat(dueText?.replace(/[^0-9.]/g, '') ?? '0')
    await page.fill('#payment-amount', due.toFixed(2))
  }
  await page.click('#add-payment')
}

test.describe('POS Offline Workflows', () => {
  test.beforeEach(async ({ page }) => {
    await registerDefaultRoutes(page)
    await page.goto('/admin/pos')
    await completePairingFlow(page)
  })

  test('supports split tender workflow online', async ({ page }) => {
    await addWidgetToCart(page)

    // First payment: card
    await page.selectOption('#payment-method', 'card')
    await page.fill('#payment-amount', '10.00')
    await page.fill('#payment-reference', '4242')
    await page.click('#add-payment')

    // Second payment: cash for remainder
    await page.selectOption('#payment-method', 'cash')
    await recordCashPayment(page)

    await expect(page.locator('.payment-pill')).toHaveCount(2)

    await page.click('button:has-text("Complete Sale")')
    await expect(page.getByText('Sale Queued')).toBeVisible()
    await expect(page.getByText('Transaction syncing to server')).toBeVisible()
  })

  test('queues transaction while offline and flushes on reconnect', async ({
    page,
    context,
  }) => {
    await context.setOffline(true)
    await page.waitForTimeout(500)
    await expect(page.locator('.offline-indicator')).toContainText('Offline')

    await addWidgetToCart(page)
    await recordCashPayment(page)
    await page.click('button:has-text("Complete Sale")')
    await expect(page.getByText('Transaction will sync when online')).toBeVisible()

    const queuePanel = page.locator('[data-test="offline-queue-panel"]')
    await expect(queuePanel.locator('[data-test="queue-entry"]')).toHaveCount(1)

    await context.setOffline(false)
    await page.waitForTimeout(1000)
    await expect(page.locator('.offline-indicator')).toContainText('Online', { timeout: 15000 })
    await expect(queuePanel.locator('[data-test="queue-entry"]')).toHaveCount(0, { timeout: 15000 })
  })

  test('retries failed syncs and allows manual retry', async ({ page }) => {
    await page.unroute('**/api/pos/offline/upload')
    let attempts = 0
    await page.route('**/api/pos/offline/upload', async (route) => {
      attempts += 1
      if (attempts === 1) {
        await route.fulfill({ status: 500, body: 'Server error' })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ enqueued: 1, duplicates: 0 }),
        })
      }
    })

    await addWidgetToCart(page)
    await recordCashPayment(page)
    await page.click('button:has-text("Complete Sale")')

    const failedEntry = page.locator('.queue-item.status-failed')
    await expect(failedEntry).toBeVisible({ timeout: 10000 })
    await expect(page.locator('text=Next retry:')).toBeVisible()

    await page.click('.btn-retry')
    await expect(page.locator('.queue-item.status-synced')).toBeVisible({ timeout: 10000 })
  })

  test('exports encrypted queue backup', async ({ page, context }) => {
    await context.setOffline(true)
    await addWidgetToCart(page)
    await recordCashPayment(page)
    await page.click('button:has-text("Complete Sale")')

    const downloadPromise = page.waitForEvent('download')
    await page.click('button[title*="Export encrypted backup"]')
    const download = await downloadPromise
    expect(download.suggestedFilename()).toMatch(/encrypted-queue-.*\.json/)

    await expect(page.getByText('Queue Exported')).toBeVisible()
    await context.setOffline(false)
  })
})
