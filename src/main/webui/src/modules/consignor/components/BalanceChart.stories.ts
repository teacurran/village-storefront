/**
 * Storybook stories for BalanceChart component
 *
 * Demonstrates balance overview widget with different states.
 *
 * References:
 * - Task I3.T7: Storybook stories for consignor portal
 */

import type { Meta, StoryObj } from '@storybook/vue3'
import BalanceChart from './BalanceChart.vue'

const meta: Meta<typeof BalanceChart> = {
  title: 'Consignor/BalanceChart',
  component: BalanceChart,
  tags: ['autodocs'],
}

export default meta
type Story = StoryObj<typeof BalanceChart>

export const EligibleForPayout: Story = {
  args: {
    balanceOwed: {
      amount: 12500, // $125.00
      currency: 'USD',
    },
    lifetimeEarnings: {
      amount: 450000, // $4,500.00
      currency: 'USD',
    },
    avgCommissionRate: 25,
    lastPayoutDate: '2025-12-15T10:00:00Z',
    nextPayoutEligible: true,
  },
}

export const BelowMinimum: Story = {
  args: {
    balanceOwed: {
      amount: 3500, // $35.00
      currency: 'USD',
    },
    lifetimeEarnings: {
      amount: 125000, // $1,250.00
      currency: 'USD',
    },
    avgCommissionRate: 20,
    lastPayoutDate: '2025-11-01T10:00:00Z',
    nextPayoutEligible: false,
  },
}

export const NewConsignor: Story = {
  args: {
    balanceOwed: {
      amount: 0,
      currency: 'USD',
    },
    lifetimeEarnings: {
      amount: 0,
      currency: 'USD',
    },
    avgCommissionRate: 25,
    nextPayoutEligible: false,
  },
}

export const HighEarner: Story = {
  args: {
    balanceOwed: {
      amount: 234560, // $2,345.60
      currency: 'USD',
    },
    lifetimeEarnings: {
      amount: 9876540, // $98,765.40
      currency: 'USD',
    },
    avgCommissionRate: 30,
    lastPayoutDate: '2026-01-01T10:00:00Z',
    nextPayoutEligible: true,
  },
}
