# Village Storefront Admin SPA

Modern admin dashboard for Village Storefront built with Vue 3, Vite, PrimeVue, and Tailwind CSS.

## Overview

The Admin SPA provides a responsive, feature-rich interface for managing multi-tenant ecommerce stores. It includes:

- **Authentication & Multi-Tenancy**: JWT-based auth with tenant context resolution and impersonation support
- **State Management**: Pinia stores for auth, tenant, and catalog with server state caching
- **Component Library**: Reusable base components styled with Tailwind and platform design tokens
- **Command Palette**: Quick actions via keyboard shortcuts (⌘K/Ctrl+K)
- **API Integration**: Type-safe API client generated from OpenAPI spec
- **Development Tools**: Storybook for component documentation, Vitest for testing

## Quick Start

### Prerequisites

- Node.js 18+ and npm 9+
- Running Quarkus backend at `http://localhost:8080`

### Installation

```bash
# From repository root
npm run spa:install

# Or directly in SPA directory
cd src/main/webui
npm install
```

### Development

```bash
# Start dev server (from root)
npm run spa:dev

# Or from SPA directory
cd src/main/webui
npm run dev
```

The dev server will start at `http://localhost:5173` with API proxying to the Quarkus backend.

### Building for Production

```bash
# From root
npm run spa:build

# Or from SPA directory
cd src/main/webui
npm run build
```

Build output goes to `target/classes/META-INF/resources/admin` for Quinoa integration.

## NPM Scripts

### Core Scripts

| Command | Description |
|---------|-------------|
| `npm run dev` | Start Vite dev server with HMR |
| `npm run build` | Build production bundle with hashed assets |
| `npm run preview` | Preview production build locally |
| `npm run type-check` | Run TypeScript type checking |
| `npm run lint` | Lint code with ESLint (auto-fix enabled) |
| `npm run format` | Format code with Prettier |

### Testing

| Command | Description |
|---------|-------------|
| `npm run test` | Run Vitest unit tests in watch mode |
| `npm run test:ui` | Run tests with Vitest UI |
| `npm run test:coverage` | Generate coverage report |

Vitest suites currently cover routing/auth flows (`tests/admin/AdminShell.spec.ts`) plus Storybook atom snapshots (`tests/components/BaseAtoms.spec.ts`) to guard the design system.

### Storybook

| Command | Description |
|---------|-------------|
| `npm run storybook` | Start Storybook dev server on port 6006 |
| `npm run storybook:build` | Build static Storybook site |

### API Client Generation

| Command | Description |
|---------|-------------|
| `npm run generate:api` | Generate TypeScript client from OpenAPI spec |

Run this after updating `api/v1/openapi.yaml` to regenerate type-safe API bindings.

## Project Structure

```
src/main/webui/
├── .storybook/               # Storybook configuration
├── src/
│   ├── api/                  # API client and types
│   │   ├── client.ts         # Axios-based API wrapper
│   │   ├── types.ts          # Shared TypeScript interfaces
│   │   └── generated/        # Auto-generated from OpenAPI
│   ├── assets/               # Static assets and global styles
│   │   └── main.css          # Tailwind imports
│   ├── components/
│   │   ├── base/             # Reusable base components
│   │   │   ├── BaseButton.vue
│   │   │   ├── BaseInput.vue
│   │   │   ├── BaseSelect.vue
│   │   │   ├── MetricsCard.vue
│   │   │   ├── InlineAlert.vue
│   │   │   └── *.stories.ts  # Storybook stories
│   │   └── CommandPalette.vue
│   ├── composables/          # Vue composition functions
│   ├── layouts/
│   │   └── DefaultLayout.vue # Main app shell
│   ├── router/
│   │   └── index.ts          # Route definitions + guards
│   ├── stores/               # Pinia state stores
│   │   ├── auth.ts           # Authentication & impersonation
│   │   ├── tenant.ts         # Multi-tenant context
│   │   └── catalog.ts        # Product catalog state
│   ├── telemetry/            # SPA telemetry event bus
│   │   └── index.ts
│   ├── views/                # Page components
│   │   ├── DashboardView.vue
│   │   ├── CatalogView.vue
│   │   ├── POSView.vue
│   │   ├── SettingsView.vue
│   │   └── LoginView.vue
│   ├── App.vue               # Root component
│   └── main.ts               # Application entry point
├── tests/
│   ├── admin/
│   │   └── AdminShell.spec.ts # Integration tests
│   └── components/
│       └── BaseAtoms.spec.ts  # Snapshot tests for base atoms
├── index.html                # HTML entry point
├── vite.config.ts            # Vite configuration
├── vitest.config.ts          # Vitest configuration
├── tailwind.config.cjs       # Tailwind CSS configuration
├── postcss.config.cjs        # PostCSS configuration
├── tsconfig.json             # TypeScript configuration
├── .eslintrc.cjs             # ESLint configuration
├── .prettierrc.json          # Prettier configuration
└── package.json              # Dependencies and scripts
```

## Architecture

### State Management (Pinia)

The application uses Pinia for state management with three core stores:

#### Auth Store (`src/stores/auth.ts`)

Manages user authentication, JWT tokens, and impersonation context:

```typescript
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()

// Login
await authStore.login('user@example.com', 'password')

// Check authentication
if (authStore.isAuthenticated) {
  console.log('User:', authStore.user)
}

// Handle impersonation
authStore.setImpersonation({
  adminUserId: 'admin-1',
  adminEmail: 'admin@platform.com',
  reason: 'Customer support',
  startedAt: new Date().toISOString(),
})

// Logout
authStore.logout()
```

#### Tenant Store (`src/stores/tenant.ts`)

Manages multi-tenant context, design tokens, and feature flags:

```typescript
import { useTenantStore } from '@/stores/tenant'

const tenantStore = useTenantStore()

// Load tenant context
await tenantStore.loadTenant()

// Check feature flags
if (tenantStore.isFeatureEnabled('loyalty')) {
  // Show loyalty features
}

// Access tenant info
console.log(tenantStore.tenantName) // "Demo Store"
console.log(tenantStore.tenantPlan) // "PRO"
```

#### Catalog Store (`src/stores/catalog.ts`)

Manages product catalog state with caching and filtering:

```typescript
import { useCatalogStore } from '@/stores/catalog'

const catalogStore = useCatalogStore()

// Load products
await catalogStore.loadProducts(1)

// Apply filters
catalogStore.setFilters({ status: 'ACTIVE', search: 'shirt' })

// Select products
catalogStore.selectProduct('product-1')
catalogStore.selectAllProducts()
```

### Routing

Routes are defined in `src/router/index.ts` with authentication guards:

```typescript
// Protected route
{
  path: '/catalog',
  component: CatalogView,
  meta: { requiresAuth: true }
}

// Public route
{
  path: '/login',
  component: LoginView,
  meta: { requiresAuth: false }
}
```

Navigation guard automatically redirects unauthenticated users to `/login`.

### Design Tokens

The SPA mirrors platform design tokens from the root `tailwind.config.js`. Tokens are applied as CSS custom properties and can be overridden per tenant:

```css
/* Uses platform tokens */
.primary-button {
  background-color: var(--color-primary-600);
}
```

Tenant-specific tokens are loaded dynamically via the Tenant Store and applied to the document root.

### API Client

The API client wraps Axios with automatic:

- JWT token injection
- Tenant context headers
- Token refresh on 401
- Error handling

```typescript
import { apiClient } from '@/api/client'

// GET request
const products = await apiClient.get<Product[]>('/products')

// POST request
const newProduct = await apiClient.post('/products', { name: 'New Product' })
```

Generated types from OpenAPI are in `src/api/generated/`.

## Telemetry & Observability

SPA bootstrap emits telemetry events (per Architecture Section 14) via `src/telemetry/index.ts`. Events fire for:

- `app:hydrated` – app load time + version metadata (emitted in `main.ts`)
- `route:change` – router transitions including tenant/auth context
- `command-palette:*` – palette toggles and command executions
- `impersonation:banner` – impersonation banner visibility with admin email

Listen for events with:

```ts
import { onTelemetryEvent } from '@/telemetry'

const unsubscribe = onTelemetryEvent((event) => {
  if (event.name === 'route:change') {
    console.log(event.payload.to)
  }
})
```

The same events also dispatch a `vsf:telemetry` CustomEvent on `window` for Quinoa/analytics listeners.

## Component Library

### BaseButton

```vue
<BaseButton
  variant="primary"
  size="md"
  :loading="isSubmitting"
  @click="handleClick"
>
  Save Changes
</BaseButton>
```

**Props**: `variant`, `size`, `type`, `disabled`, `loading`, `fullWidth`

### BaseInput

```vue
<BaseInput
  v-model="email"
  type="email"
  label="Email Address"
  placeholder="user@example.com"
  hint="We'll never share your email"
  :error="emailError"
  required
/>
```

**Props**: `modelValue`, `type`, `label`, `placeholder`, `hint`, `error`, `disabled`, `required`, `size`

### BaseSelect

```vue
<BaseSelect
  v-model="category"
  :options="categories"
  label="Category"
  placeholder="Choose a category"
/>
```

**Props**: `modelValue`, `options`, `label`, `placeholder`, `hint`, `error`, `disabled`, `required`, `size`

### MetricsCard

```vue
<MetricsCard
  title="Total Revenue"
  :value="29584.50"
  format="currency"
  :change="12.5"
  timeframe="Last 30 days"
  :show-sparkline="true"
  :sparkline-data="[100, 120, 115, 134, 168]"
/>
```

**Props**: `title`, `value`, `change`, `timeframe`, `format`, `currency`, `showSparkline`, `sparklineData`

### InlineAlert

```vue
<InlineAlert
  tone="warning"
  title="Action Required"
  description="Your payment method expires soon"
  :dismissible="true"
  :actions="[
    { label: 'Update', onClick: () => router.push('/billing') }
  ]"
/>
```

**Props**: `tone`, `title`, `description`, `actions`, `dismissible`, `persistDismissal`

## Testing

### Unit Tests

Tests use Vitest and Vue Test Utils:

```typescript
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import BaseButton from '@/components/base/BaseButton.vue'

describe('BaseButton', () => {
  it('renders slot content', () => {
    const wrapper = mount(BaseButton, {
      slots: { default: 'Click Me' }
    })
    expect(wrapper.text()).toBe('Click Me')
  })
})
```

### Integration Tests

See `tests/admin/AdminShell.spec.ts` for examples of testing routing, stores, and component integration.

### Coverage Requirements

Maintain ≥80% line and branch coverage per VillageCompute standards. Run:

```bash
npm run test:coverage
```

## CI Integration

The GitHub Actions workflow (`.github/workflows/ci.yml`) includes an `admin-spa` job that:

1. Detects `src/main/webui/package.json` existence
2. Installs dependencies with npm ci
3. Runs linting (`npm run lint`)
4. Runs tests (`npm test -- --runInBand`)
5. Uploads test results and coverage

Workflow expects Node 20 and caches `node_modules` based on `package-lock.json`.

## Quinoa Integration

Vite build outputs to `target/classes/META-INF/resources/admin`, where Quarkus Quinoa serves it. Assets are hashed for cache busting.

In production, the SPA is served at `/admin/*` paths.

## Storybook

Component documentation and interactive demos are available via Storybook:

```bash
npm run storybook
```

Visit `http://localhost:6006` to browse components with interactive controls (knobs).

### Writing Stories

Stories use CSF 3.0 format:

```typescript
import type { Meta, StoryObj } from '@storybook/vue3'
import MyComponent from './MyComponent.vue'

const meta: Meta<typeof MyComponent> = {
  title: 'Components/MyComponent',
  component: MyComponent,
  tags: ['autodocs'],
}

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  args: {
    prop1: 'value',
  },
}
```

## Troubleshooting

### Dev server won't start

- Ensure Quarkus backend is running at `http://localhost:8080`
- Check port 5173 is available
- Clear `node_modules` and reinstall

### API calls fail with CORS errors

- Verify Vite proxy config in `vite.config.ts`
- Check Quarkus CORS configuration

### Build fails with TypeScript errors

- Run `npm run type-check` for detailed errors
- Ensure all dependencies are installed
- Check `tsconfig.json` paths

### Tests fail with "Cannot find module" errors

- Verify Vitest config resolves `@/` alias
- Check imports use correct casing
- Ensure test setup includes global plugins (Pinia, Router)

## Related Documentation

- [Architecture Overview](../../../docs/architecture_overview.md)
- [Java Project Standards](../../../docs/java-project-standards.adoc)
- [OpenAPI Specification](../../../api/v1/openapi.yaml)
- [Tailwind Configuration](../../../tailwind.config.js)

## License

Proprietary - VillageCompute
