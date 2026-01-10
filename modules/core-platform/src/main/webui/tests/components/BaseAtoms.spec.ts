import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import InlineAlert from '@/components/base/InlineAlert.vue'
import MetricsCard from '@/components/base/MetricsCard.vue'
import { getGlobalPlugins } from '../helpers'

describe('Base atoms snapshots', () => {
  it('BaseButton default', () => {
    const wrapper = mount(BaseButton, {
      slots: {
        default: 'Primary Action',
      },
    })
    expect(wrapper.html()).toMatchSnapshot()
  })

  it('BaseInput with hint', () => {
    const wrapper = mount(BaseInput, {
      props: {
        label: 'Email',
        modelValue: 'admin@example.com',
        hint: 'We never share your email.',
      },
      global: {
        plugins: getGlobalPlugins(),
      },
    })
    expect(wrapper.html()).toMatchSnapshot()
  })

  it('BaseSelect renders options', () => {
    const wrapper = mount(BaseSelect, {
      props: {
        label: 'Status',
        options: [
          { label: 'Draft', value: 'draft' },
          { label: 'Published', value: 'published' },
        ],
        modelValue: 'draft',
      },
      global: {
        plugins: getGlobalPlugins(),
      },
    })
    expect(wrapper.html()).toMatchSnapshot()
  })

  it('InlineAlert default', () => {
    const wrapper = mount(InlineAlert, {
      props: {
        tone: 'info',
        title: 'Heads up',
        description: 'Snapshot coverage for base atoms.',
      },
    })
    expect(wrapper.html()).toMatchSnapshot()
  })

  it('MetricsCard with sparkline', () => {
    const wrapper = mount(MetricsCard, {
      props: {
        title: 'Revenue',
        value: 25000,
        format: 'currency',
        change: 12.5,
        timeframe: 'Last 30 days',
        showSparkline: true,
        sparklineData: [10, 15, 18, 20, 22, 24, 26],
      },
    })
    expect(wrapper.html()).toMatchSnapshot()
  })
})
