import type { Meta, StoryObj } from '@storybook/vue3'
import { ref } from 'vue'
import BaseInput from './BaseInput.vue'

const meta = {
  title: 'Base/Input',
  component: BaseInput,
  tags: ['autodocs'],
  argTypes: {
    type: {
      control: 'select',
      options: ['text', 'email', 'password', 'number', 'tel', 'url', 'search'],
    },
    size: {
      control: 'select',
      options: ['sm', 'md', 'lg'],
    },
    disabled: { control: 'boolean' },
    required: { control: 'boolean' },
  },
  args: {
    type: 'text',
    size: 'md',
    disabled: false,
    required: false,
  },
} satisfies Meta<typeof BaseInput>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  render: (args) => ({
    components: { BaseInput },
    setup() {
      const value = ref('')
      return { args, value }
    },
    template: '<BaseInput v-bind="args" v-model="value" label="Default Input" />',
  }),
}

export const WithPlaceholder: Story = {
  render: (args) => ({
    components: { BaseInput },
    setup() {
      const value = ref('')
      return { args, value }
    },
    template: `
      <BaseInput
        v-bind="args"
        v-model="value"
        label="Email"
        placeholder="user@example.com"
        type="email"
      />
    `,
  }),
}

export const WithHint: Story = {
  render: (args) => ({
    components: { BaseInput },
    setup() {
      const value = ref('')
      return { args, value }
    },
    template: `
      <BaseInput
        v-bind="args"
        v-model="value"
        label="Username"
        hint="Choose a unique username"
      />
    `,
  }),
}

export const WithError: Story = {
  render: (args) => ({
    components: { BaseInput },
    setup() {
      const value = ref('invalid')
      return { args, value }
    },
    template: `
      <BaseInput
        v-bind="args"
        v-model="value"
        label="Email"
        error="Please enter a valid email address"
      />
    `,
  }),
}

export const Sizes: Story = {
  render: () => ({
    components: { BaseInput },
    setup() {
      const small = ref('')
      const medium = ref('')
      const large = ref('')
      return { small, medium, large }
    },
    template: `
      <div class="flex flex-col gap-4">
        <BaseInput v-model="small" label="Small" size="sm" />
        <BaseInput v-model="medium" label="Medium" size="md" />
        <BaseInput v-model="large" label="Large" size="lg" />
      </div>
    `,
  }),
}

export const Required: Story = {
  render: (args) => ({
    components: { BaseInput },
    setup() {
      const value = ref('')
      return { args, value }
    },
    template: '<BaseInput v-bind="args" v-model="value" label="Required Field" required />',
  }),
}
