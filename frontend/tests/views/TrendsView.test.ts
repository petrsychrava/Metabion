import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../msw/server'
import TrendsView from '@/views/TrendsView.vue'
import en from '@/i18n/en.json'
import type { DailyTrendResponse, MeasurementPoint } from '@/types/api'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

function point(value: number, unit: MeasurementPoint['unit']): MeasurementPoint {
  return {
    id: 1, measurementType: 'GLUCOSE', value, unit,
    measuredAt: '2026-07-20T08:00:00', context: 'FASTING',
  }
}

function trendResponse(): DailyTrendResponse {
  return {
    patientProfileId: 1,
    from: '2026-07-20',
    to: '2026-07-20',
    glucoseUnit: 'MMOL_L',
    timezone: 'Europe/Prague',
    days: [
      {
        date: '2026-07-20',
        symptomCheckInId: null,
        symptomScore: null,
        flareState: null,
        dietLogId: null,
        adherenceLevel: null,
        appetiteLevel: null,
        glucoseMeasurements: [point(5.0, 'MMOL_L'), point(180, 'MG_DL')],
        ketoneMeasurements: [],
      },
    ],
  }
}

const lineStub = { name: 'Line', props: ['data', 'options'], template: '<div class="chart-stub" />' }

describe('TrendsView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('converts mixed-unit glucose points to the trend unit before averaging', async () => {
    server.use(http.get('/api/trends/daily', () => HttpResponse.json(trendResponse())))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/trends', component: TrendsView }],
    })
    await router.push('/trends')
    const wrapper = mount(TrendsView, { global: { plugins: [createPinia(), i18n, router], stubs: { Line: lineStub } } })
    await flushPromises()

    const charts = wrapper.findAllComponents({ name: 'Line' })
    expect(charts).toHaveLength(3)
    // 5.0 mmol/L + 180 mg/dL (= 10 mmol/L) averaged in mmol/L → 7.5, not 92.5
    expect(charts[1].props('data').datasets[0].data).toEqual([7.5])
  })
})
