# Admin SPA Development Guide

## Overview

The Village Storefront Admin SPA is a Vue 3 single-page application built with Vite, PrimeVue, and Pinia. It provides a comprehensive dashboard for managing stores, products, orders, and other ecommerce operations.

## Tech Stack

- **Framework**: Vue 3 (Composition API with `<script setup>`)
- **Build Tool**: Vite 5
- **State Management**: Pinia
- **UI Library**: PrimeVue 3
- **Styling**: Tailwind CSS 3
- **Router**: Vue Router 4
- **API Client**: Axios with OpenAPI-generated types
- **Testing**: Vitest (unit), Cypress (e2e)
- **Integration**: Quinoa (Quarkus asset serving)

## Project Structure

```
modules/core-platform/src/main/webui/
├── src/
│   ├── api/
│   │   ├── client.ts              # API client wrapper
│   │   ├── types.ts               # Shared API types
│   │   └── generated/             # OpenAPI-generated types/services
│   ├── assets/                    # Static assets (images, fonts)
│   ├── components/
│   │   ├── base/                  # Base/atomic components
│   │   └── [feature]/             # Feature-specific components
│   ├── layouts/
│   │   └── DefaultLayout.vue      # Admin shell layout
│   ├── router/
│   │   └── index.ts               # Route definitions
│   ├── stores/
│   │   ├── auth.ts                # Authentication state
│   │   ├── tenant.ts              # Tenant/feature flag state
│   │   ├── catalog.ts             # Product catalog state
│   │   └── [feature].ts           # Other domain stores
│   ├── views/
│   │   ├── DashboardView.vue      # Main dashboard
│   │   ├── Products/
│   │   │   ├── ProductList.vue    # Product listing
│   │   │   └── ProductEditor.vue  # Product create/edit
│   │   └── [feature]/             # Other views
│   ├── telemetry.ts               # Analytics/telemetry helpers
│   ├── main.ts                    # App entry point
│   └── App.vue                    # Root component
├── cypress/
│   └── e2e/                       # E2E tests
├── tests/
│   └── [feature]/                 # Unit/integration tests
├── public/                        # Public static files
├── package.json                   # NPM dependencies
├── vite.config.ts                 # Vite configuration
├── tailwind.config.js             # Tailwind configuration
└── tsconfig.json                  # TypeScript configuration
```

## Development Workflow

### Prerequisites

- Node.js >= 18
- npm >= 9
- Java 21 (for running Quarkus backend)

### Initial Setup

```bash
# Navigate to admin SPA directory
cd modules/core-platform/src/main/webui

# Install dependencies
npm ci

# Generate API client from OpenAPI spec
npm run generate:api
```

### Development Mode

#### Option 1: Standalone Vite Dev Server (Recommended)

```bash
# Start Vite dev server (port 5173)
npm run dev

# In another terminal, start Quarkus backend (port 8080)
cd ../../../../..
./mvnw quarkus:dev
```

The Vite dev server proxies `/api/*` requests to the Quarkus backend at `http://localhost:8080`.

**Benefits**:
- Hot module replacement (HMR)
- Fast refresh
- Instant feedback on code changes

#### Option 2: Quinoa Integration (Production-like)

```bash
# Start Quarkus with Quinoa (builds and serves admin SPA)
./mvnw quarkus:dev
```

Quinoa automatically:
- Installs NPM dependencies
- Runs Vite build on changes
- Serves built assets at `/admin/*`

**Benefits**:
- Matches production deployment
- Tests full integration
- Validates Quinoa configuration

### Building for Production

```bash
# Build admin SPA
npm run build

# Output: modules/core-platform/target/classes/META-INF/resources/admin/
```

The build process:
- Bundles all assets with Vite
- Generates hashed filenames for cache busting
- Creates source maps for debugging
- Optimizes for production (minification, tree-shaking)

### Running Tests

#### Unit Tests (Vitest)

```bash
# Run all unit tests
npm test

# Run tests in watch mode
npm test -- --watch

# Run with coverage
npm run test:coverage

# Run tests with UI
npm run test:ui
```

#### E2E Tests (Cypress)

```bash
# Install Cypress (first time only)
npx cypress install

# Open Cypress Test Runner
npx cypress open

# Run all E2E tests headless
npx cypress run
```

### Code Quality

```bash
# Run ESLint
npm run lint

# Format code with Prettier
npm run format

# Type check
npm run type-check
```

## Key Concepts

### State Management with Pinia

Stores follow a consistent pattern:

```typescript
// stores/example.ts
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { apiClient } from '@/api/client'

export const useExampleStore = defineStore('example', () => {
  // State (ref)
  const items = ref<Item[]>([])
  const loading = ref(false)

  // Computed
  const itemCount = computed(() => items.value.length)

  // Actions
  async function fetchItems() {
    loading.value = true
    try {
      items.value = await apiClient.get('/api/items')
    } finally {
      loading.value = false
    }
  }

  return { items, loading, itemCount, fetchItems }
})
```

**Best Practices**:
- Use composition API style (`setup()` function)
- Separate server state (products, orders) from UI state (filters, pagination)
- Implement caching with TTL for frequently accessed data
- Handle loading/error states explicitly

### API Integration

The admin SPA uses a custom API client that wraps Axios:

```typescript
import { apiClient } from '@/api/client'

// GET request
const products = await apiClient.get<Product[]>('/admin/catalog/products')

// POST request
const newProduct = await apiClient.post<Product>('/admin/catalog/products', data)

// PUT request
const updated = await apiClient.put<Product>(`/admin/catalog/products/${id}`, data)

// DELETE request
await apiClient.delete(`/admin/catalog/products/${id}`)
```

**Features**:
- Automatic JWT token injection
- Tenant context headers
- Token refresh on 401
- Request/response interceptors
- TypeScript type safety

### Routing and Navigation

Routes are defined in `src/router/index.ts`:

```typescript
{
  path: '/catalog/products',
  name: 'catalog-products',
  component: () => import('@/views/Products/ProductList.vue'),
  meta: {
    title: 'Products',
    requiresAuth: true,
    // Optional guards:
    requiredRole: 'ADMIN',
    featureFlag: 'advanced-catalog',
  },
}
```

**Navigation Guards**:
- `requiresAuth`: Redirects to login if not authenticated
- `requiredRole`: Checks user has specific role
- `requiresVendorRole`: For consignor portal routes
- `featureFlag`: Checks tenant feature flag

### Component Patterns

#### Views (Page Components)

```vue
<template>
  <div class="view-container">
    <h1>Page Title</h1>
    <!-- Content -->
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useExampleStore } from '@/stores/example'

const store = useExampleStore()

onMounted(async () => {
  await store.fetchItems()
})
</script>
```

#### Base Components

Reusable, generic components in `components/base/`:
- `BaseButton.vue`
- `BaseInput.vue`
- `BaseSelect.vue`
- `MetricsCard.vue`
- `InlineAlert.vue`

#### Feature Components

Feature-specific components organized by domain:
- `components/catalog/ProductCard.vue`
- `components/orders/OrderTimeline.vue`
- `components/pos/PaymentKeypad.vue`

### Styling with Tailwind

Use Tailwind utility classes for styling:

```vue
<template>
  <div class="max-w-7xl mx-auto px-4">
    <button class="px-4 py-2 bg-primary-600 text-white rounded-md hover:bg-primary-700">
      Click Me
    </button>
  </div>
</template>
```

**Theme Colors**:
- `primary-*`: Brand blue (#2563eb)
- `neutral-*`: Grays
- `danger-*`: Red for errors/destructive actions
- `success-*`: Green for success states
- `warning-*`: Yellow/orange for warnings

### Telemetry and Analytics

Emit telemetry events for user actions:

```typescript
import { emitTelemetryEvent } from '@/telemetry'

emitTelemetryEvent('catalog:product:create', {
  productId: '123',
  category: 'clothing',
})
```

Events are batched and sent to the analytics backend.

## Common Tasks

### Adding a New View

1. Create view component in `src/views/[Feature]/ViewName.vue`
2. Add route in `src/router/index.ts`
3. Add navigation link in `src/layouts/DefaultLayout.vue`
4. Create tests in `cypress/e2e/[feature].cy.ts`

### Adding a New Store

1. Create store in `src/stores/[feature].ts`
2. Define types for state/actions
3. Implement async actions with API calls
4. Add unit tests in `src/stores/__tests__/[feature].spec.ts`
5. Use store in components with `const store = useFeatureStore()`

### Integrating a New API Endpoint

1. Update OpenAPI spec in `api/v1/openapi.yaml`
2. Regenerate API client: `npm run generate:api`
3. Import generated types/services in store or component
4. Call API through `apiClient` wrapper

### Adding a PrimeVue Component

```vue
<script setup lang="ts">
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
</script>

<template>
  <DataTable :value="items">
    <Column field="name" header="Name" />
    <Column field="status" header="Status" />
  </DataTable>
</template>
```

PrimeVue components are globally registered and styled via the theme imported in `main.ts`.

## Best Practices

### Performance

- Lazy-load routes with `() => import()`
- Use `v-once` for static content
- Implement virtual scrolling for long lists (PrimeVue VirtualScroller)
- Cache API responses in stores with TTL
- Use `v-memo` for expensive renders

### Accessibility

- Use semantic HTML (`<button>`, `<nav>`, `<main>`)
- Add ARIA labels for icon-only buttons
- Ensure keyboard navigation works
- Use PrimeVue's built-in accessibility features
- Test with screen readers

### Security

- Never store sensitive data in localStorage (only JWTs)
- Sanitize user input
- Use CSRF tokens for state-changing operations
- Validate data on both client and server
- Implement rate limiting for API calls

### Testing

- Write unit tests for stores and utilities
- Write component tests for complex interactions
- Write E2E tests for critical user flows
- Aim for 80%+ code coverage
- Mock API calls in tests

## Troubleshooting

### Hot Module Replacement Not Working

- Ensure Vite dev server is running on port 5173
- Check for console errors
- Restart dev server: `npm run dev`

### API Calls Failing

- Verify Quarkus backend is running on port 8080
- Check proxy configuration in `vite.config.ts`
- Inspect network tab for error responses
- Verify JWT token is valid

### Build Fails

- Clear `node_modules` and reinstall: `rm -rf node_modules && npm ci`
- Clear Vite cache: `rm -rf node_modules/.vite`
- Check for TypeScript errors: `npm run type-check`

### Tests Failing

- Ensure all dependencies are installed
- Check for API mocks in tests
- Clear test cache: `npx vitest --clearCache`
- Run tests in isolation to debug

## Deployment

The admin SPA is deployed as part of the Quarkus application:

1. Build process generates static assets in `target/classes/META-INF/resources/admin/`
2. Quarkus serves these assets at `/admin/*` path
3. SPA routing is handled by `quarkus.quinoa.enable-spa-routing=true`
4. All API requests go to same Quarkus server (no CORS issues)

### Production Build Checklist

- [ ] Run `npm run lint` and fix issues
- [ ] Run `npm run type-check` and fix errors
- [ ] Run `npm test` and ensure all tests pass
- [ ] Run `npm run build` successfully
- [ ] Test production build locally with Quarkus
- [ ] Verify source maps are generated
- [ ] Check bundle size is reasonable
- [ ] Test in multiple browsers

## Resources

- [Vue 3 Documentation](https://vuejs.org/)
- [Pinia Documentation](https://pinia.vuejs.org/)
- [PrimeVue Documentation](https://primevue.org/)
- [Vite Documentation](https://vitejs.dev/)
- [Tailwind CSS Documentation](https://tailwindcss.com/)
- [Quarkus Quinoa Guide](https://quarkiverse.github.io/quarkiverse-docs/quarkus-quinoa/dev/)
