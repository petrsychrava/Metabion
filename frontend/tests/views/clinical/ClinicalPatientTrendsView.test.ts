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

  it('drops a stale trend response when two applies race', async () => {
    const staleTrend = { ...trend, glucoseUnit: 'MG_DL' }
    let call = 0
    let resolveHeld: (response: HttpResponse<typeof staleTrend>) => void = () => undefined
    server.use(
      http.get('/api/clinical/trends/daily', () => {
        call += 1
        // The mount load stays in flight until the test releases it.
        if (call === 1) {
          return new Promise<HttpResponse<typeof staleTrend>>((resolve) => {
            resolveHeld = resolve
          })
        }
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

    // The view's only button is Apply; it races the still-in-flight mount load.
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(en.enums.MeasurementUnit.MMOL_L)

    // The stale mount response (a different unit) lands now and must be dropped.
    resolveHeld(HttpResponse.json(staleTrend))
    await flushPromises()
    expect(wrapper.text()).toContain(en.enums.MeasurementUnit.MMOL_L)
    expect(wrapper.text()).not.toContain(en.enums.MeasurementUnit.MG_DL)
  })

  it('clears loading and drops the in-flight response when the applied range is invalid', async () => {
    let resolveHeld: (response: HttpResponse<typeof trend>) => void = () => undefined
    server.use(
      http.get('/api/clinical/trends/daily', () =>
        new Promise<HttpResponse<typeof trend>>((resolve) => {
          resolveHeld = resolve
        }),
      ),
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
    expect(wrapper.text()).toContain(en.common.loading)

    const dateInputs = wrapper.findAll('input[type="date"]')
    await dateInputs[0].setValue('2026-08-03')
    await dateInputs[1].setValue('2026-08-01')
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.date_range_invalid)
    expect(wrapper.text()).not.toContain(en.common.loading)

    resolveHeld(HttpResponse.json(trend))
    await flushPromises()
    expect(wrapper.text()).not.toContain(en.trends.symptomScore)
  })
})
