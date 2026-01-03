import type { Meta, StoryObj } from '@storybook/vue3'
import MetricsCard from './MetricsCard.vue'

const meta = {
  title: 'Base/MetricsCard',
  component: MetricsCard,
  tags: ['autodocs'],
  argTypes: {
    format: {
      control: 'select',
      options: ['number', 'currency', 'percentage'],
    },
    showSparkline: { control: 'boolean' },
  },
  args: {
    format: 'number',
    showSparkline: false,
  },
} satisfies Meta<typeof MetricsCard>

export default meta
type Story = StoryObj<typeof meta>

export const Revenue: Story = {
  args: {
    title: 'Total Revenue',
    value: 29584.5,
    format: 'currency',
    change: 12.5,
    timeframe: 'Last 30 days',
  },
}

export const Orders: Story = {
  args: {
    title: 'Orders',
    value: 1247,
    change: 8.2,
    timeframe: 'Last 30 days',
  },
}

export const ConversionRate: Story = {
  args: {
    title: 'Conversion Rate',
    value: 3.24,
    format: 'percentage',
    change: -2.1,
    timeframe: 'Last 30 days',
  },
}

export const WithSparkline: Story = {
  args: {
    title: 'Revenue Trend',
    value: 45890,
    format: 'currency',
    change: 15.3,
    timeframe: 'Last 7 days',
    showSparkline: true,
    sparklineData: [100, 120, 115, 134, 168, 132, 200],
  },
}

export const NegativeChange: Story = {
  args: {
    title: 'Bounce Rate',
    value: 42.5,
    format: 'percentage',
    change: -5.2,
    timeframe: 'Last 30 days',
    showSparkline: true,
    sparklineData: [50, 48, 45, 44, 43, 42, 40],
  },
}

export const AllFormats: Story = {
  render: () => ({
    components: { MetricsCard },
    template: `
      <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
        <MetricsCard
          title="Total Sales"
          :value="15842"
          format="number"
          :change="8.5"
        />
        <MetricsCard
          title="Revenue"
          :value="234567.89"
          format="currency"
          :change="12.3"
        />
        <MetricsCard
          title="Conversion"
          :value="3.45"
          format="percentage"
          :change="-1.2"
        />
      </div>
    `,
  }),
}
