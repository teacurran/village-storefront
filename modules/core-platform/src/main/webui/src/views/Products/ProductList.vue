<template>
  <div class="product-list-view">
    <!-- Header -->
    <div class="mb-6 flex items-center justify-between">
      <div>
        <h1 class="text-2xl font-bold text-neutral-900">Products</h1>
        <p class="text-sm text-neutral-600 mt-1">
          Manage your product catalog, variants, and inventory
        </p>
      </div>
      <BaseButton variant="primary" @click="handleCreateProduct">
        <svg class="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M12 4v16m8-8H4"
          />
        </svg>
        New Product
      </BaseButton>
    </div>

    <!-- Filters -->
    <div class="bg-white rounded-lg border border-neutral-200 p-4 mb-6">
      <div class="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div>
          <label class="block text-sm font-medium text-neutral-700 mb-1">Search</label>
          <input
            v-model="filters.search"
            type="text"
            placeholder="Search by name or SKU..."
            class="w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-transparent"
            @input="handleFilterChange"
          />
        </div>
        <div>
          <label class="block text-sm font-medium text-neutral-700 mb-1">Category</label>
          <select
            v-model="filters.category"
            class="w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-transparent"
            @change="handleFilterChange"
          >
            <option value="">All Categories</option>
            <option v-for="cat in categories" :key="cat.id" :value="cat.id">
              {{ cat.name }}
            </option>
          </select>
        </div>
        <div>
          <label class="block text-sm font-medium text-neutral-700 mb-1">Status</label>
          <select
            v-model="filters.status"
            class="w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-transparent"
            @change="handleFilterChange"
          >
            <option value="">All Statuses</option>
            <option value="active">Active</option>
            <option value="draft">Draft</option>
            <option value="archived">Archived</option>
          </select>
        </div>
        <div class="flex items-end">
          <BaseButton variant="neutral" class="w-full" @click="handleClearFilters">
            Clear Filters
          </BaseButton>
        </div>
      </div>
    </div>

    <!-- Data Table -->
    <div class="bg-white rounded-lg border border-neutral-200 overflow-hidden">
      <div v-if="showSkeleton" class="divide-y divide-neutral-100">
        <div
          v-for="index in 6"
          :key="`skeleton-row-${index}`"
          class="flex items-center gap-4 px-4 py-4"
        >
          <Skeleton shape="circle" size="3rem" class="flex-shrink-0" />
          <div class="flex-1 space-y-2">
            <Skeleton width="40%" height="1.25rem" />
            <Skeleton width="25%" height="1rem" />
          </div>
          <Skeleton width="6rem" height="1.25rem" />
          <Skeleton width="4rem" height="1rem" />
          <Skeleton width="5rem" height="1rem" />
        </div>
      </div>

      <DataTable
        v-else
        :value="products"
        :loading="loading"
        :paginator="true"
        :rows="pagination.pageSize"
        :total-records="pagination.totalItems"
        :lazy="true"
        :first="(pagination.page - 1) * pagination.pageSize"
        :sort-field="sortField"
        :sort-order="sortOrder"
        striped-rows
        responsive-layout="scroll"
        data-cy="product-table"
        @page="handlePageChange"
        @sort="handleSortChange"
      >
        <template #empty>
          <div class="text-center py-8 text-neutral-500">
            <svg
              class="w-16 h-16 mx-auto mb-4 text-neutral-300"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4"
              />
            </svg>
            <p class="text-lg font-medium">No products found</p>
            <p class="text-sm mt-1">Get started by creating your first product</p>
          </div>
        </template>

        <Column field="image" header="Image" style="width: 80px">
          <template #body="{ data }">
            <img
              v-if="data.primaryImage"
              :src="data.primaryImage.thumbnailUrl"
              :alt="data.name"
              class="w-12 h-12 rounded object-cover"
            />
            <div
              v-else
              class="w-12 h-12 rounded bg-neutral-100 flex items-center justify-center text-neutral-400"
            >
              <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"
                />
              </svg>
            </div>
          </template>
        </Column>

        <Column field="name" header="Product" sortable>
          <template #body="{ data }">
            <div>
              <router-link
                :to="{ name: 'catalog-products-edit', params: { id: data.id } }"
                class="font-medium text-primary-600 hover:text-primary-700"
              >
                {{ data.name }}
              </router-link>
              <p v-if="data.sku" class="text-xs text-neutral-500 mt-1">SKU: {{ data.sku }}</p>
            </div>
          </template>
        </Column>

        <Column field="status" header="Status" sortable style="width: 120px">
          <template #body="{ data }">
            <Tag :value="data.status" :severity="getStatusSeverity(data.status)" class="text-xs" />
          </template>
        </Column>

        <Column field="price" header="Price" sortable style="width: 120px">
          <template #body="{ data }">
            <span v-if="data.price" class="font-medium">
              {{ formatMoney(data.price) }}
            </span>
            <span v-else class="text-neutral-400 text-sm">—</span>
          </template>
        </Column>

        <Column field="inventory" header="Inventory" sortable style="width: 120px">
          <template #body="{ data }">
            <span :class="getInventoryClass(data)">
              {{ data.totalInventory ?? 0 }}
            </span>
            <span
              v-if="data.trackInventory && typeof data.lowStockThreshold === 'number'"
              class="text-xs text-neutral-500 ml-1"
            >
              / {{ data.lowStockThreshold }}
            </span>
          </template>
        </Column>

        <Column field="variants" header="Variants" style="width: 100px">
          <template #body="{ data }">
            <span class="text-sm text-neutral-600">{{ data.variantCount || 0 }}</span>
          </template>
        </Column>

        <Column field="updatedAt" header="Updated" sortable style="width: 140px">
          <template #body="{ data }">
            <span class="text-sm text-neutral-600">{{ formatDate(data.updatedAt) }}</span>
          </template>
        </Column>

        <Column header="Actions" style="width: 100px">
          <template #body="{ data }">
            <div class="flex gap-2">
              <button
                class="p-2 text-neutral-600 hover:text-primary-600 rounded hover:bg-neutral-100"
                title="Edit"
                @click="handleEditProduct(data.id)"
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"
                  />
                </svg>
              </button>
              <button
                class="p-2 text-neutral-600 hover:text-danger-600 rounded hover:bg-neutral-100"
                title="Delete"
                @click="handleDeleteProduct(data.id)"
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                  />
                </svg>
              </button>
            </div>
          </template>
        </Column>
      </DataTable>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useDebounceFn } from '@vueuse/core'
import { useCatalogStore } from '@/stores/catalog'
import type { Product, CatalogFilters } from '@/stores/catalog'
import { useToast } from 'primevue/usetoast'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Tag from 'primevue/tag'
import Skeleton from 'primevue/skeleton'
import BaseButton from '@/components/base/BaseButton.vue'
import { emitTelemetryEvent } from '@/telemetry'
import type { DataTablePageEvent } from 'primevue/datatable'
import type { DataTableSortEvent } from 'primevue/datatable'

const router = useRouter()
const catalogStore = useCatalogStore()
const toast = useToast()

const createFilterState = (): CatalogFilters => ({
  search: catalogStore.currentFilters.search ?? '',
  category: catalogStore.currentFilters.category ?? '',
  status: catalogStore.currentFilters.status ?? '',
})
const filters = ref<CatalogFilters>(createFilterState())

const sortField = ref(catalogStore.sortField || 'updatedAt')
const sortOrder = ref(catalogStore.sortOrder === 'asc' ? 1 : -1)

const products = computed(() => catalogStore.products)
const categories = computed(() => catalogStore.categories)
const pagination = computed(() => catalogStore.pagination)
const loading = computed(() => catalogStore.isLoadingProducts)
const showSkeleton = computed(() => loading.value && products.value.length === 0)

// Load products on mount
onMounted(async () => {
  await loadCategories()
  await loadProducts()
})

watch(
  () => [
    catalogStore.currentFilters.search,
    catalogStore.currentFilters.category,
    catalogStore.currentFilters.status,
  ],
  () => {
    filters.value = createFilterState()
  }
)

watch(
  () => [catalogStore.sortField, catalogStore.sortOrder],
  ([field, order]) => {
    sortField.value = field || 'updatedAt'
    sortOrder.value = order === 'asc' ? 1 : -1
  }
)

const debouncedFilterFetch = useDebounceFn(async () => {
  await loadProducts()
}, 300)

async function loadProducts() {
  try {
    await catalogStore.fetchProducts()
    emitTelemetryEvent('catalog:list:load', {
      count: products.value.length,
      filters: { ...catalogStore.currentFilters },
      page: catalogStore.pagination.page,
    })
  } catch (error) {
    console.error('Failed to load products:', error)
    toast.add({
      severity: 'error',
      summary: 'Error',
      detail: 'Failed to load products. Please try again.',
      life: 5000,
    })
  }
}

async function loadCategories() {
  try {
    await catalogStore.fetchCategories()
  } catch (error) {
    console.error('Failed to load categories:', error)
    toast.add({
      severity: 'warn',
      summary: 'Warning',
      detail: 'Unable to load categories for filtering.',
      life: 4000,
    })
  }
}

function handleFilterChange() {
  catalogStore.setFilters({ ...filters.value })
  debouncedFilterFetch()
}

async function handleClearFilters() {
  filters.value = {
    search: '',
    category: '',
    status: '',
  }
  catalogStore.clearFilters()
  debouncedFilterFetch.cancel()
  await loadProducts()
}

async function handlePageChange(event: DataTablePageEvent) {
  if (event.rows !== pagination.value.pageSize) {
    catalogStore.setPageSize(event.rows)
  }
  catalogStore.setPage(event.page + 1)
  await loadProducts()
}

async function handleSortChange(event: DataTableSortEvent) {
  const field = (event.sortField as string) || 'updatedAt'
  sortField.value = field
  const eventOrder = event.sortOrder ?? -1
  sortOrder.value = eventOrder
  catalogStore.setSort(field, eventOrder === 1 ? 'asc' : 'desc')
  await loadProducts()
}

function handleCreateProduct() {
  emitTelemetryEvent('catalog:product:create-click')
  router.push({ name: 'catalog-products-new' })
}

function handleEditProduct(productId: string) {
  emitTelemetryEvent('catalog:product:edit-click', { productId })
  router.push({ name: 'catalog-products-edit', params: { id: productId } })
}

async function handleDeleteProduct(productId: string) {
  if (!confirm('Are you sure you want to delete this product? This action cannot be undone.')) {
    return
  }

  try {
    await catalogStore.deleteProduct(productId)
    await loadProducts()
    toast.add({
      severity: 'success',
      summary: 'Success',
      detail: 'Product deleted successfully',
      life: 3000,
    })
    emitTelemetryEvent('catalog:product:delete', { productId })
  } catch (error) {
    console.error('Failed to delete product:', error)
    toast.add({
      severity: 'error',
      summary: 'Error',
      detail: 'Failed to delete product. Please try again.',
      life: 5000,
    })
  }
}

function getStatusSeverity(status: string): string {
  const severityMap: Record<string, string> = {
    active: 'success',
    draft: 'warning',
    archived: 'secondary',
  }
  return severityMap[status] || 'info'
}

function getInventoryClass(product: Product): string {
  if (!product.trackInventory) {
    return 'text-neutral-700'
  }

  if (typeof product.lowStockThreshold !== 'number') {
    return 'text-neutral-700'
  }

  const total = product.totalInventory ?? 0
  return total <= product.lowStockThreshold ? 'text-danger-600 font-medium' : 'text-neutral-700'
}

function formatMoney(amount: { value: number; currency: string }): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: amount.currency,
  }).format(amount.value / 100)
}

function formatDate(date: string): string {
  return new Intl.DateTimeFormat('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  }).format(new Date(date))
}
</script>
