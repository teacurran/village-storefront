<template>
  <div class="product-editor-view">
    <!-- Header -->
    <div class="mb-6 flex items-center justify-between">
      <div>
        <div class="flex items-center gap-3">
          <button
            class="p-2 text-neutral-600 hover:text-neutral-900 rounded-md hover:bg-neutral-100"
            @click="handleBack"
          >
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7" />
            </svg>
          </button>
          <h1 class="text-2xl font-bold text-neutral-900">
            {{ isEditMode ? 'Edit Product' : 'New Product' }}
          </h1>
        </div>
        <p v-if="product.name" class="text-sm text-neutral-600 mt-1 ml-14">
          {{ product.name }}
        </p>
      </div>
      <div class="flex gap-3">
        <BaseButton variant="neutral" @click="handleCancel">Cancel</BaseButton>
        <BaseButton variant="primary" :disabled="saving" @click="handleSave">
          {{ saving ? 'Saving...' : 'Save Product' }}
        </BaseButton>
      </div>
    </div>

    <!-- Form -->
    <div class="grid grid-cols-3 gap-6">
      <!-- Main Content -->
      <div class="col-span-2 space-y-6">
        <!-- Basic Information -->
        <div class="bg-white rounded-lg border border-neutral-200 p-6">
          <h2 class="text-lg font-semibold text-neutral-900 mb-4">Basic Information</h2>
          <div class="space-y-4">
            <div>
              <label class="block text-sm font-medium text-neutral-700 mb-1">
                Product Name *
              </label>
              <input
                v-model="product.name"
                type="text"
                required
                placeholder="e.g., Classic Cotton T-Shirt"
                class="w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                :class="{ 'border-danger-500': errors.name }"
              />
              <p v-if="errors.name" class="text-sm text-danger-600 mt-1">{{ errors.name }}</p>
            </div>

            <div>
              <label class="block text-sm font-medium text-neutral-700 mb-1">Description</label>
              <textarea
                v-model="product.description"
                rows="4"
                placeholder="Describe your product..."
                class="w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-transparent"
              />
            </div>
          </div>
        </div>

        <!-- Pricing -->
        <div class="bg-white rounded-lg border border-neutral-200 p-6">
          <h2 class="text-lg font-semibold text-neutral-900 mb-4">Pricing</h2>
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="block text-sm font-medium text-neutral-700 mb-1">Price *</label>
              <div class="relative">
                <span class="absolute left-3 top-2 text-neutral-500">$</span>
                <input
                  v-model="priceInput"
                  type="number"
                  step="0.01"
                  min="0"
                  required
                  placeholder="0.00"
                  class="w-full pl-7 pr-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                  :class="{ 'border-danger-500': errors.price }"
                />
              </div>
              <p v-if="errors.price" class="text-sm text-danger-600 mt-1">{{ errors.price }}</p>
            </div>

            <div>
              <label class="block text-sm font-medium text-neutral-700 mb-1">
                Compare at Price
              </label>
              <div class="relative">
                <span class="absolute left-3 top-2 text-neutral-500">$</span>
                <input
                  v-model="compareAtPriceInput"
                  type="number"
                  step="0.01"
                  min="0"
                  placeholder="0.00"
                  class="w-full pl-7 pr-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                />
              </div>
            </div>
          </div>
        </div>

        <!-- Inventory -->
        <div class="bg-white rounded-lg border border-neutral-200 p-6">
          <h2 class="text-lg font-semibold text-neutral-900 mb-4">Inventory</h2>
          <div class="space-y-4">
            <div>
              <label class="block text-sm font-medium text-neutral-700 mb-1">SKU</label>
              <input
                v-model="product.sku"
                type="text"
                placeholder="e.g., SHIRT-BLK-M"
                class="w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-transparent"
              />
            </div>

            <div class="flex items-center">
              <input
                id="trackInventory"
                v-model="product.trackInventory"
                type="checkbox"
                class="w-4 h-4 text-primary-600 border-neutral-300 rounded focus:ring-primary-500"
              />
              <label for="trackInventory" class="ml-2 text-sm text-neutral-700">
                Track inventory quantity
              </label>
            </div>

            <div v-if="product.trackInventory" class="grid grid-cols-2 gap-4">
              <div>
                <label class="block text-sm font-medium text-neutral-700 mb-1">
                  Stock Quantity
                </label>
                <input
                  v-model.number="product.totalInventory"
                  type="number"
                  min="0"
                  placeholder="0"
                  class="w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                />
              </div>

              <div>
                <label class="block text-sm font-medium text-neutral-700 mb-1">
                  Low Stock Threshold
                </label>
                <input
                  v-model.number="product.lowStockThreshold"
                  type="number"
                  min="0"
                  placeholder="5"
                  class="w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                />
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Sidebar -->
      <div class="col-span-1 space-y-6">
        <!-- Status -->
        <div class="bg-white rounded-lg border border-neutral-200 p-6">
          <h2 class="text-lg font-semibold text-neutral-900 mb-4">Status</h2>
          <select
            v-model="product.status"
            class="w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-transparent"
          >
            <option value="draft">Draft</option>
            <option value="active">Active</option>
            <option value="archived">Archived</option>
          </select>
          <p class="text-sm text-neutral-600 mt-2">
            {{ statusDescription }}
          </p>
        </div>

        <!-- Product Organization -->
        <div class="bg-white rounded-lg border border-neutral-200 p-6">
          <h2 class="text-lg font-semibold text-neutral-900 mb-4">Organization</h2>
          <div class="space-y-4">
            <div>
              <label class="block text-sm font-medium text-neutral-700 mb-1">Category</label>
              <select
                v-model="product.categoryId"
                class="w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-transparent"
              >
                <option value="">No category</option>
                <option v-for="cat in categories" :key="cat.id" :value="cat.id">
                  {{ cat.name }}
                </option>
              </select>
            </div>
          </div>
        </div>

        <!-- Media Placeholder -->
        <div class="bg-white rounded-lg border border-neutral-200 p-6">
          <h2 class="text-lg font-semibold text-neutral-900 mb-4">Media</h2>
          <div class="border-2 border-dashed border-neutral-300 rounded-lg p-8 text-center">
            <svg class="w-12 h-12 mx-auto text-neutral-400 mb-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
            </svg>
            <p class="text-sm text-neutral-600">Image upload coming soon</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useCatalogStore } from '@/stores/catalog'
import { useToast } from 'primevue/usetoast'
import BaseButton from '@/components/base/BaseButton.vue'
import { emitTelemetryEvent } from '@/telemetry'

const router = useRouter()
const route = useRoute()
const catalogStore = useCatalogStore()
const toast = useToast()

const isEditMode = computed(() => !!route.params.id)
const saving = ref(false)

const product = ref({
  id: '',
  name: '',
  slug: '',
  sku: '',
  description: '',
  price: { value: 0, currency: 'USD' },
  compareAtPrice: undefined as { value: number; currency: string } | undefined,
  status: 'draft' as 'draft' | 'active' | 'archived',
  trackInventory: true,
  totalInventory: 0,
  lowStockThreshold: 5,
  categoryId: '',
})

const priceInput = ref('0.00')
const compareAtPriceInput = ref('')

const errors = ref<Record<string, string>>({})

const categories = computed(() => catalogStore.categories)

const statusDescription = computed(() => {
  const descriptions = {
    draft: 'Not visible to customers. Use for products under development.',
    active: 'Visible and purchasable by customers.',
    archived: 'Hidden from customers but kept for records.',
  }
  return descriptions[product.value.status]
})

onMounted(async () => {
  // Load categories
  try {
    await catalogStore.fetchCategories()
  } catch (error) {
    console.error('Failed to load categories:', error)
  }

  // Load product if editing
  if (isEditMode.value) {
    await loadProduct()
  }
})

async function loadProduct() {
  const productId = route.params.id as string
  try {
    const loadedProduct = await catalogStore.fetchProductById(productId)
    if (loadedProduct) {
      product.value = {
        id: loadedProduct.id || '',
        name: loadedProduct.name || '',
        slug: loadedProduct.slug || '',
        sku: loadedProduct.sku || '',
        description: loadedProduct.description || '',
        price: loadedProduct.price || { value: 0, currency: 'USD' },
        compareAtPrice: loadedProduct.compareAtPrice,
        status: (loadedProduct.status?.toLowerCase() || 'draft') as any,
        trackInventory: loadedProduct.trackInventory ?? true,
        totalInventory: loadedProduct.totalInventory ?? 0,
        lowStockThreshold: loadedProduct.lowStockThreshold ?? 5,
        categoryId: loadedProduct.categoryId || '',
      }

      priceInput.value = loadedProduct.price
        ? (loadedProduct.price.value / 100).toFixed(2)
        : '0.00'
      compareAtPriceInput.value = loadedProduct.compareAtPrice
        ? (loadedProduct.compareAtPrice.value / 100).toFixed(2)
        : ''

      emitTelemetryEvent('catalog:product:edit-load', { productId })
      return
    }

    toast.add({
      severity: 'error',
      summary: 'Error',
      detail: 'Product not found.',
      life: 5000,
    })
    navigateToList()
  } catch (error) {
    console.error('Failed to load product:', error)
    toast.add({
      severity: 'error',
      summary: 'Error',
      detail: 'Failed to load product. Please try again.',
      life: 5000,
    })
    navigateToList()
  }
}

function validateForm(): boolean {
  errors.value = {}

  if (!product.value.name || product.value.name.trim() === '') {
    errors.value.name = 'Product name is required'
  }

  if (!priceInput.value || parseFloat(priceInput.value) < 0) {
    errors.value.price = 'Valid price is required'
  }

  return Object.keys(errors.value).length === 0
}

async function handleSave() {
  if (!validateForm()) {
    toast.add({
      severity: 'warn',
      summary: 'Validation Error',
      detail: 'Please fix the errors before saving.',
      life: 5000,
    })
    return
  }

  saving.value = true

  try {
    // Convert price inputs to cents
    const priceValue = Math.round(parseFloat(priceInput.value) * 100)
    const compareAtPriceValue = compareAtPriceInput.value
      ? Math.round(parseFloat(compareAtPriceInput.value) * 100)
      : undefined

    const productData = {
      name: product.value.name,
      slug: product.value.slug || generateSlug(product.value.name),
      sku: product.value.sku || undefined,
      description: product.value.description || undefined,
      price: { value: priceValue, currency: 'USD' },
      compareAtPrice: compareAtPriceValue
        ? { value: compareAtPriceValue, currency: 'USD' }
        : undefined,
      status: product.value.status.toUpperCase(),
      trackInventory: product.value.trackInventory,
      totalInventory: product.value.totalInventory,
      lowStockThreshold: product.value.lowStockThreshold,
      categoryId: product.value.categoryId || undefined,
    }

    if (isEditMode.value) {
      await catalogStore.updateProduct(product.value.id, productData)
      emitTelemetryEvent('catalog:product:update', { productId: product.value.id })
    } else {
      const newProduct = await catalogStore.createProduct(productData)
      emitTelemetryEvent('catalog:product:create', { productId: newProduct.id })
    }

    toast.add({
      severity: 'success',
      summary: 'Success',
      detail: `Product ${isEditMode.value ? 'updated' : 'created'} successfully`,
      life: 3000,
    })

    navigateToList()
  } catch (error) {
    console.error('Failed to save product:', error)
    toast.add({
      severity: 'error',
      summary: 'Error',
      detail: `Failed to ${isEditMode.value ? 'update' : 'create'} product. Please try again.`,
      life: 5000,
    })
  } finally {
    saving.value = false
  }
}

function handleCancel() {
  if (confirm('Are you sure you want to discard your changes?')) {
    navigateToList()
  }
}

function handleBack() {
  navigateToList()
}

function generateSlug(name: string): string {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/(^-|-$)/g, '')
}

function navigateToList() {
  router.push({ name: 'catalog-products' })
}
</script>
