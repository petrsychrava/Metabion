import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalCheckInsView from '@/views/clinical/ClinicalCheckInsView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const summaries = [
  {
    patientProfileId: 41,
    patientEmail: 'patient@example.com',
    date: '2026-08-03',
    dietLogId: 7,
    adherenceLevel: 'FULL',
    appetiteLevel: 'NORMAL',
    mealCount: 3,
    deviationCount: 0,
    measurementCount: 2,
    symptomCheckInId: 9,
    symptomScore: 4,
    flareState: 'NO_FLARE',
  },
  {
    patientProfileId: 41,
    patientEmail: 'patient@example.com',
    date: '2026-08-02',
    dietLogId: null,
    adherenceLevel: null,
    appetiteLevel: null,
    mealCount: null,
    deviationCount: null,
    measurementCount: null,
    symptomCheckInId: 8,
    symptomScore: 11,
    flareState: 'SUSPECTED_FLARE',
  },
]

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical/patients/:patientProfileId/check-ins', component: ClinicalCheckInsView },
      { path: '/clinical/patients/:patientProfileId/check-ins/:date', component: { template: '<div />' } },
    ],
  })
}

describe('ClinicalCheckInsView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('loads the default range and renders both halves per day', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/clinical/daily-check-ins', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json(summaries)
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/patients/41/check-ins?email=patient%40example.com')
    const wrapper = mount(ClinicalCheckInsView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(seenUrl).toContain('patientProfileId=41')
    const rows = wrapper.findAll('[data-testid="checkin-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain(en.enums.DietAdherenceLevel.FULL)
    expect(rows[1].text()).toContain(en.checkIn.FlareState.SUSPECTED_FLARE)
    expect(rows[1].text()).toContain(en.clinical.noValue)
  })

  it('opens the day detail with the email query preserved', async () => {
    server.use(http.get('/api/clinical/daily-check-ins', () => HttpResponse.json(summaries)))
    const router = makeRouter()
    await router.push('/clinical/patients/41/check-ins?email=patient%40example.com')
    const wrapper = mount(ClinicalCheckInsView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.findAll('[data-testid="checkin-row"]')[1].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/clinical/patients/41/check-ins/2026-08-02')
    expect(router.currentRoute.value.query.email).toBe('patient@example.com')
  })

  it('drops a stale list response when two applies race', async () => {
    const staleRows = [{ ...summaries[0], date: '2026-07-01' }]
    const freshRows = [{ ...summaries[0], date: '2026-08-01' }]
    let call = 0
    let resolveHeld: (response: HttpResponse<typeof staleRows>) => void = () => undefined
    server.use(
      http.get('/api/clinical/daily-check-ins', () => {
        call += 1
        if (call === 1) return HttpResponse.json([])
        // The first Apply stays in flight until the test releases it.
        if (call === 2) {
          return new Promise<HttpResponse<typeof staleRows>>((resolve) => {
            resolveHeld = resolve
          })
        }
        return HttpResponse.json(freshRows)
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/patients/41/check-ins')
    const wrapper = mount(ClinicalCheckInsView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    // The view's only button is Apply; two rapid clicks race two range loads.
    await wrapper.find('button').trigger('click')
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('2026-08-01')

    // The stale first response lands now and must not overwrite the fresh list.
    resolveHeld(HttpResponse.json(staleRows))
    await flushPromises()
    expect(wrapper.text()).toContain('2026-08-01')
    expect(wrapper.text()).not.toContain('2026-07-01')
  })

  it('clears loading and drops the in-flight response when the applied range is invalid', async () => {
    let resolveHeld: (response: HttpResponse<typeof summaries>) => void = () => undefined
    server.use(
      http.get('/api/clinical/daily-check-ins', () =>
        new Promise<HttpResponse<typeof summaries>>((resolve) => {
          resolveHeld = resolve
        }),
      ),
    )
    const router = makeRouter()
    await router.push('/clinical/patients/41/check-ins')
    const wrapper = mount(ClinicalCheckInsView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.text()).toContain(en.common.loading)

    const dateInputs = wrapper.findAll('input[type="date"]')
    await dateInputs[0].setValue('2026-08-03')
    await dateInputs[1].setValue('2026-08-01')
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.date_range_invalid)
    expect(wrapper.text()).not.toContain(en.common.loading)

    resolveHeld(HttpResponse.json(summaries))
    await flushPromises()
    expect(wrapper.findAll('[data-testid="checkin-row"]')).toHaveLength(0)
  })
})
