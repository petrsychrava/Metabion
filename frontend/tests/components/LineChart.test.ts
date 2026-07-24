import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import LineChart from '@/components/LineChart.vue'

describe('LineChart', () => {
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
})
