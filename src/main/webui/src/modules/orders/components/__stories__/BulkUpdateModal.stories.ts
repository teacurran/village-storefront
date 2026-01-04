/**
 * BulkUpdateModal Storybook Stories
 *
 * Visual documentation for the bulk order update modal component.
 *
 * References:
 * - Task I5.T1: Storybook stories requirement
 */

import type { Meta, StoryObj } from '@storybook/vue3'
import BulkUpdateModal from '../BulkUpdateModal.vue'

const meta: Meta<typeof BulkUpdateModal> = {
  title: 'Admin/Orders/BulkUpdateModal',
  component: BulkUpdateModal,
  tags: ['autodocs'],
  argTypes: {
    isOpen: {
      description: 'Controls modal visibility',
      control: 'boolean',
    },
    selectedCount: {
      description: 'Number of selected orders',
      control: 'number',
    },
  },
}

export default meta
type Story = StoryObj<typeof BulkUpdateModal>

/**
 * Default state with 3 selected orders
 */
export const Default: Story = {
  args: {
    isOpen: true,
    selectedCount: 3,
  },
}

/**
 * Single order selected
 */
export const SingleOrder: Story = {
  args: {
    isOpen: true,
    selectedCount: 1,
  },
}

/**
 * Many orders selected
 */
export const ManyOrders: Story = {
  args: {
    isOpen: true,
    selectedCount: 25,
  },
}

/**
 * Closed state
 */
export const Closed: Story = {
  args: {
    isOpen: false,
    selectedCount: 3,
  },
}
