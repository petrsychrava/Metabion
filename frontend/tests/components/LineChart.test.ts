import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import LineChart from '@/components/LineChart.vue'
import { isDark } from '@/theme'

describe('LineChart', () => {
  beforeEach(() => {
    isDark.value = false
  })

  it('passes labels and datasets to the chart', () => {
    const wrapper = mount(LineChart, {
      props: {
        labels: ['2026-07-01', '2026-07-02'],
        datasets: [{ label: 'Symptom score', data: [3, null] }],
      },
      global: {
        stubs: {
          Line: {
            name: 'Line',
            props: ['data', 'options'],
            template: '<div class="chart-stub" />',
          },
        },
      },
    })
    const line = wrapper.findComponent({ name: 'Line' })
    expect(line.props('data').labels).toEqual(['2026-07-01', '2026-07-02'])
    expect(line.props('data').datasets[0].data).toEqual([3, null])
  })

  it('adapts grid and tick colors to the dark theme', () => {
    isDark.value = true
    const wrapper = mount(LineChart, {
      props: {
        labels: ['2026-07-01'],
        datasets: [{ label: 'Symptom score', data: [3] }],
      },
      global: {
        stubs: {
          Line: {
            name: 'Line',
            props: ['data', 'options'],
            template: '<div class="chart-stub" />',
          },
        },
      },
    })
    const options = wrapper.findComponent({ name: 'Line' }).props('options')
    expect(options.scales.y.ticks.color).toBe('#d1d5db')
    expect(options.scales.y.grid.color).toBe('#374151')
  })
})
