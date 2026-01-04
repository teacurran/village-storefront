import type { Meta, StoryObj } from '@storybook/vue3'
import BaseButton from './BaseButton.vue'

const meta = {
  title: 'Base/Button',
  component: BaseButton,
  tags: ['autodocs'],
  argTypes: {
    variant: {
      control: 'select',
      options: ['primary', 'secondary', 'success', 'warning', 'error', 'neutral'],
    },
    size: {
      control: 'select',
      options: ['sm', 'md', 'lg'],
    },
    type: {
      control: 'select',
      options: ['button', 'submit', 'reset'],
    },
    disabled: { control: 'boolean' },
    loading: { control: 'boolean' },
    fullWidth: { control: 'boolean' },
  },
  args: {
    variant: 'primary',
    size: 'md',
    type: 'button',
    disabled: false,
    loading: false,
    fullWidth: false,
  },
} satisfies Meta<typeof BaseButton>

export default meta
type Story = StoryObj<typeof meta>

export const Primary: Story = {
  args: {
    variant: 'primary',
  },
  render: (args) => ({
    components: { BaseButton },
    setup() {
      return { args }
    },
    template: '<BaseButton v-bind="args">Primary Button</BaseButton>',
  }),
}

export const Secondary: Story = {
  args: {
    variant: 'secondary',
  },
  render: (args) => ({
    components: { BaseButton },
    setup() {
      return { args }
    },
    template: '<BaseButton v-bind="args">Secondary Button</BaseButton>',
  }),
}

export const AllVariants: Story = {
  render: () => ({
    components: { BaseButton },
    template: `
      <div class="flex flex-col gap-4">
        <BaseButton variant="primary">Primary</BaseButton>
        <BaseButton variant="secondary">Secondary</BaseButton>
        <BaseButton variant="success">Success</BaseButton>
        <BaseButton variant="warning">Warning</BaseButton>
        <BaseButton variant="error">Error</BaseButton>
        <BaseButton variant="neutral">Neutral</BaseButton>
      </div>
    `,
  }),
}

export const Sizes: Story = {
  render: () => ({
    components: { BaseButton },
    template: `
      <div class="flex items-center gap-4">
        <BaseButton size="sm">Small</BaseButton>
        <BaseButton size="md">Medium</BaseButton>
        <BaseButton size="lg">Large</BaseButton>
      </div>
    `,
  }),
}

export const States: Story = {
  render: () => ({
    components: { BaseButton },
    template: `
      <div class="flex flex-col gap-4">
        <BaseButton>Normal</BaseButton>
        <BaseButton disabled>Disabled</BaseButton>
        <BaseButton loading>Loading</BaseButton>
        <BaseButton full-width>Full Width</BaseButton>
      </div>
    `,
  }),
}
