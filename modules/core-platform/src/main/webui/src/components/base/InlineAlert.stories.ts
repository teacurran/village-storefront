import type { Meta, StoryObj } from '@storybook/vue3'
import InlineAlert from './InlineAlert.vue'

const meta = {
  title: 'Base/InlineAlert',
  component: InlineAlert,
  tags: ['autodocs'],
  argTypes: {
    tone: {
      control: 'select',
      options: ['info', 'success', 'warning', 'error'],
    },
    dismissible: { control: 'boolean' },
    persistDismissal: { control: 'boolean' },
  },
  args: {
    tone: 'info',
    dismissible: false,
    persistDismissal: false,
  },
} satisfies Meta<typeof InlineAlert>

export default meta
type Story = StoryObj<typeof meta>

export const Info: Story = {
  args: {
    tone: 'info',
    title: 'Information',
    description: 'This is an informational alert message.',
  },
}

export const Success: Story = {
  args: {
    tone: 'success',
    title: 'Success!',
    description: 'Your changes have been saved successfully.',
  },
}

export const Warning: Story = {
  args: {
    tone: 'warning',
    title: 'Warning',
    description: 'You are approaching your monthly quota limit.',
  },
}

export const Error: Story = {
  args: {
    tone: 'error',
    title: 'Error',
    description: 'Failed to process your request. Please try again.',
  },
}

export const Dismissible: Story = {
  args: {
    tone: 'info',
    title: 'Tip',
    description: 'You can dismiss this message by clicking the X button.',
    dismissible: true,
  },
}

export const WithActions: Story = {
  args: {
    tone: 'warning',
    title: 'Update Available',
    description: 'A new version of the application is available.',
    actions: [
      {
        label: 'Update Now',
        onClick: () => alert('Updating...'),
      },
      {
        label: 'Remind Me Later',
        onClick: () => alert('Will remind later'),
      },
    ],
  },
}

export const AllTones: Story = {
  render: () => ({
    components: { InlineAlert },
    template: `
      <div class="flex flex-col gap-4">
        <InlineAlert
          tone="info"
          title="Information"
          description="This is an informational message"
        />
        <InlineAlert
          tone="success"
          title="Success"
          description="Operation completed successfully"
        />
        <InlineAlert
          tone="warning"
          title="Warning"
          description="Please review before proceeding"
        />
        <InlineAlert
          tone="error"
          title="Error"
          description="An error occurred during processing"
        />
      </div>
    `,
  }),
}
