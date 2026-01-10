/**
 * Storybook stories for DashboardStatsCard component
 *
 * Demonstrates various configurations and states of the stats card widget.
 *
 * References:
 * - Task I3.T7: Storybook stories for consignor portal
 */

import type { Meta, StoryObj } from '@storybook/vue3'
import DashboardStatsCard from './DashboardStatsCard.vue'

const meta: Meta<typeof DashboardStatsCard> = {
  title: 'Consignor/DashboardStatsCard',
  component: DashboardStatsCard,
  tags: ['autodocs'],
  argTypes: {
    color: {
      control: 'select',
      options: ['primary', 'secondary', 'success', 'warning', 'error'],
    },
  },
}

export default meta
type Story = StoryObj<typeof DashboardStatsCard>

export const BalanceOwed: Story = {
  args: {
    icon: '💰',
    label: 'Balance Owed',
    value: '$1,234.56',
    color: 'primary',
  },
}

export const ActiveItems: Story = {
  args: {
    icon: '📦',
    label: 'Active Items',
    value: 42,
    subtitle: 'items in store',
    color: 'success',
  },
}

export const SoldThisMonth: Story = {
  args: {
    icon: '📈',
    label: 'Sold This Month',
    value: 18,
    subtitle: 'items',
    color: 'secondary',
  },
}

export const LifetimeEarnings: Story = {
  args: {
    icon: '💵',
    label: 'Lifetime Earnings',
    value: '$12,450.00',
    color: 'warning',
  },
}

export const ZeroBalance: Story = {
  args: {
    icon: '💰',
    label: 'Balance Owed',
    value: '$0.00',
    subtitle: 'No pending payouts',
    color: 'primary',
  },
}

export const HighValue: Story = {
  args: {
    icon: '💎',
    label: 'Premium Tier',
    value: '$99,999.99',
    subtitle: 'Congratulations!',
    color: 'error',
  },
}
