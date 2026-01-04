/**
 * MetricsCard Storybook Stories
 *
 * Reusable metrics card component used across admin dashboards.
 *
 * References:
 * - Architecture Section 2.13: Admin Component Inventory
 */

import type { Meta, StoryObj } from '@storybook/vue3'
import MetricsCard from '../MetricsCard.vue'

const meta: Meta<typeof MetricsCard> = {
  title: 'Admin/Base/MetricsCard',
  component: MetricsCard,
  tags: ['autodocs'],
  argTypes: {
    title: {
      description: 'Card title/label',
      control: 'text',
    },
    value: {
      description: 'Primary metric value to display',
      control: 'text',
    },
    icon: {
      description: 'Icon emoji or identifier',
      control: 'text',
    },
    color: {
      description: 'Color theme variant',
      control: 'select',
      options: ['primary', 'secondary', 'success', 'warning', 'error'],
    },
    change: {
      description: 'Optional percentage change indicator',
      control: 'number',
    },
  },
}

export default meta
type Story = StoryObj<typeof MetricsCard>

/**
 * Primary variant - Total Revenue
 */
export const TotalRevenue: Story = {
  args: {
    title: 'Total Revenue',
    value: '$12,500.00',
    icon: '💰',
    color: 'primary',
    change: 15.3,
  },
}

/**
 * Success variant - Orders
 */
export const TotalOrders: Story = {
  args: {
    title: 'Total Orders',
    value: '450',
    icon: '📦',
    color: 'success',
    change: 8.2,
  },
}

/**
 * Warning variant - Pending Orders
 */
export const PendingOrders: Story = {
  args: {
    title: 'Pending Orders',
    value: '12',
    icon: '⏳',
    color: 'warning',
    change: -5.1,
  },
}

/**
 * Secondary variant - Avg Order Value
 */
export const AvgOrderValue: Story = {
  args: {
    title: 'Avg Order Value',
    value: '$27.78',
    icon: '📊',
    color: 'secondary',
  },
}

/**
 * No change indicator
 */
export const NoChange: Story = {
  args: {
    title: 'Active Items',
    value: '123',
    icon: '🏷️',
    color: 'primary',
  },
}

/**
 * Negative change
 */
export const NegativeTrend: Story = {
  args: {
    title: 'Conversion Rate',
    value: '2.3%',
    icon: '📉',
    color: 'error',
    change: -12.5,
  },
}
