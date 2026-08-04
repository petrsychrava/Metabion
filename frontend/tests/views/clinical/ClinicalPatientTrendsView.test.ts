import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalPatientTrendsView from '@/views/clinical/ClinicalPatientTrendsView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const trend = {
  patientProfileId: 41,
  from: '2026-07-05',
  to: '2026-08-04',
  glucoseUnit: 'MMOL_L',
  timezone: 'Europe/Prague',
  days: [
    {
      date: '2026-08-03',
      symptomCheckInId: 9,
      symptomScore: 4,
      flareState: 'NO_FLARE',
      dietLogId: 7,
      adherenceLevel: 'FULL',
      appetiteLevel: 'NORMAL',
      glucoseMeasurements: [
        { id: 1, measurementType: 'GLUCOSE', value: 5.5, unit: 'MMOL_L', measuredAt: '2026-08-03T07:00:00Z', context: 'FASTING' },
      ],
      ketoneMeasurements: [
        { id: 2, measurementType: 'KETONE', value: 1.8, unit: 'MMOL_L', measuredAt: '2026-08-03T07:00:00Z', context: 'FASTING' },
      ],
    },
  ],
}

describe('ClinicalPatientTrendsView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('loads the clinical trend for the patient and renders the chart sections', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/clinical/trends/daily', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json(trend)
      }),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/clinical/patients/:patientProfileId/trends', component: ClinicalPatientTrendsView }],
    })
    await router.push('/clinical/patients/41/trends')
    const wrapper = mount(ClinicalPatientTrendsView, {
      global: { plugins: [createPinia(), i18n, router], stubs: { LineChart: true } },
    })
    await flushPromises()

    expect(seenUrl).toContain('patientProfileId=41')
    expect(wrapper.text()).toContain(en.trends.symptomScore)
    expect(wrapper.text()).toContain(en.trends.glucose)
    expect(wrapper.text()).toContain(en.trends.ketones)
  })
})
