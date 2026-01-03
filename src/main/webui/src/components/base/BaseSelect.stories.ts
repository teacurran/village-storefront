import type { Meta, StoryObj } from '@storybook/vue3'
import { ref } from 'vue'
import BaseSelect from './BaseSelect.vue'

const meta = {
  title: 'Base/Select',
  component: BaseSelect,
  tags: ['autodocs'],
  argTypes: {
    size: {
      control: 'select',
      options: ['sm', 'md', 'lg'],
    },
    disabled: { control: 'boolean' },
    required: { control: 'boolean' },
  },
  args: {
    size: 'md',
    disabled: false,
    required: false,
  },
} satisfies Meta<typeof BaseSelect>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  render: (args) => ({
    components: { BaseSelect },
    setup() {
      const value = ref('')
      const options = [
        { label: 'Option 1', value: '1' },
        { label: 'Option 2', value: '2' },
        { label: 'Option 3', value: '3' },
      ]
      return { args, value, options }
    },
    template: `
      <BaseSelect
        v-bind="args"
        v-model="value"
        :options="options"
        label="Select an option"
      />
    `,
  }),
}

export const WithPlaceholder: Story = {
  render: (args) => ({
    components: { BaseSelect },
    setup() {
      const value = ref('')
      const options = [
        { label: 'Small', value: 'sm' },
        { label: 'Medium', value: 'md' },
        { label: 'Large', value: 'lg' },
      ]
      return { args, value, options }
    },
    template: `
      <BaseSelect
        v-bind="args"
        v-model="value"
        :options="options"
        label="Size"
        placeholder="Choose a size"
      />
    `,
  }),
}

export const StringOptions: Story = {
  render: (args) => ({
    components: { BaseSelect },
    setup() {
      const value = ref('')
      const options = ['Apple', 'Banana', 'Orange', 'Grape']
      return { args, value, options }
    },
    template: `
      <BaseSelect
        v-bind="args"
        v-model="value"
        :options="options"
        label="Fruit"
      />
    `,
  }),
}

export const WithError: Story = {
  render: (args) => ({
    components: { BaseSelect },
    setup() {
      const value = ref('')
      const options = [
        { label: 'Admin', value: 'admin' },
        { label: 'User', value: 'user' },
      ]
      return { args, value, options }
    },
    template: `
      <BaseSelect
        v-bind="args"
        v-model="value"
        :options="options"
        label="Role"
        error="Please select a role"
      />
    `,
  }),
}

export const Sizes: Story = {
  render: () => ({
    components: { BaseSelect },
    setup() {
      const small = ref('')
      const medium = ref('')
      const large = ref('')
      const options = ['Option 1', 'Option 2', 'Option 3']
      return { small, medium, large, options }
    },
    template: `
      <div class="flex flex-col gap-4">
        <BaseSelect v-model="small" :options="options" label="Small" size="sm" />
        <BaseSelect v-model="medium" :options="options" label="Medium" size="md" />
        <BaseSelect v-model="large" :options="options" label="Large" size="lg" />
      </div>
    `,
  }),
}
