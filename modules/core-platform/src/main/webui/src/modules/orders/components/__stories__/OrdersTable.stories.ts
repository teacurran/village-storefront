/**
 * OrdersTable Storybook Stories
 *
 * Visual documentation and testing for OrdersTable component.
 * Demonstrates different states: loading, empty, with data, with selection.
 *
 * References:
 * - Task I5.T1: Storybook stories requirement
 */

import type { Meta, StoryObj } from '@storybook/vue3'
import OrdersTable from '../OrdersTable.vue'
import type { OrderItem } from '../../types'

const meta: Meta<typeof OrdersTable> = {
  title: 'Admin/Orders/OrdersTable',
  component: OrdersTable,
  tags: ['autodocs'],
  argTypes: {
    orders: {
      description: 'Array of order items to display',
    },
    loading: {
      description: 'Loading state indicator',
      control: 'boolean',
    },
    selectedIds: {
      description: 'Array of selected order IDs',
    },
  },
}

export default meta
type Story = StoryObj<typeof OrdersTable>

const mockOrders: OrderItem[] = [
  {
    id: 'order-1',
    orderNumber: 'ORD-001',
    customerName: 'John Doe',
    customerEmail: 'john@example.com',
    status: 'PENDING',
    total: { amount: 10000, currency: 'USD' },
    itemCount: 2,
    createdAt: '2026-01-01T12:00:00Z',
    updatedAt: '2026-01-01T12:00:00Z',
    paymentMethod: 'credit_card',
  },
  {
    id: 'order-2',
    orderNumber: 'ORD-002',
    customerName: 'Jane Smith',
    customerEmail: 'jane@example.com',
    status: 'SHIPPED',
    total: { amount: 15000, currency: 'USD' },
    itemCount: 3,
    createdAt: '2026-01-02T12:00:00Z',
    updatedAt: '2026-01-02T14:00:00Z',
    shippedAt: '2026-01-02T14:00:00Z',
    paymentMethod: 'paypal',
  },
  {
    id: 'order-3',
    orderNumber: 'ORD-003',
    customerName: 'Bob Johnson',
    customerEmail: 'bob@example.com',
    status: 'DELIVERED',
    total: { amount: 25000, currency: 'USD' },
    itemCount: 5,
    createdAt: '2026-01-03T10:00:00Z',
    updatedAt: '2026-01-03T16:00:00Z',
    shippedAt: '2026-01-03T12:00:00Z',
    deliveredAt: '2026-01-03T16:00:00Z',
    paymentMethod: 'credit_card',
  },
  {
    id: 'order-4',
    orderNumber: 'ORD-004',
    customerName: 'Alice Williams',
    customerEmail: 'alice@example.com',
    status: 'CANCELLED',
    total: { amount: 5000, currency: 'USD' },
    itemCount: 1,
    createdAt: '2026-01-04T09:00:00Z',
    updatedAt: '2026-01-04T10:00:00Z',
    paymentMethod: 'credit_card',
  },
]

/**
 * Default state with multiple orders
 */
export const Default: Story = {
  args: {
    orders: mockOrders,
    loading: false,
    selectedIds: [],
  },
}

/**
 * Loading state with spinner overlay
 */
export const Loading: Story = {
  args: {
    orders: mockOrders,
    loading: true,
    selectedIds: [],
  },
}

/**
 * Empty state with no orders
 */
export const Empty: Story = {
  args: {
    orders: [],
    loading: false,
    selectedIds: [],
  },
}

/**
 * With selected orders
 */
export const WithSelection: Story = {
  args: {
    orders: mockOrders,
    loading: false,
    selectedIds: ['order-1', 'order-3'],
  },
}

/**
 * Single order only
 */
export const SingleOrder: Story = {
  args: {
    orders: [mockOrders[0]],
    loading: false,
    selectedIds: [],
  },
}

/**
 * All pending orders
 */
export const AllPending: Story = {
  args: {
    orders: mockOrders.map((order) => ({ ...order, status: 'PENDING' as const })),
    loading: false,
    selectedIds: [],
  },
}

/**
 * All shipped orders
 */
export const AllShipped: Story = {
  args: {
    orders: mockOrders.map((order) => ({ ...order, status: 'SHIPPED' as const })),
    loading: false,
    selectedIds: [],
  },
}

/**
 * Large dataset (performance testing)
 */
export const LargeDataset: Story = {
  args: {
    orders: Array.from({ length: 100 }, (_, i) => ({
      ...mockOrders[0],
      id: `order-${i}`,
      orderNumber: `ORD-${String(i + 1).padStart(3, '0')}`,
    })),
    loading: false,
    selectedIds: [],
  },
}
