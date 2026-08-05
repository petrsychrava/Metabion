import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalPatientLabsView from '@/views/clinical/ClinicalPatientLabsView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const catalog = [
  { code: 'CRP', label: 'C-reactive protein', category: 'INFLAMMATION', canonicalUnit: 'mg/L', displayScale: 1, allowedUnits: ['mg/L'] },
]

const resultSets = [
  {
    id: 3,
    version: 2,
    patientProfileId: 41,
    collectionDate: '2026-07-10',
    notes: null,
    source: 'MANUAL',
    confirmationStatus: 'UNCONFIRMED',
    createdByCurrentPatient: false,
    createdAt: '2026-07-10T08:00:00Z',
    updatedAt: '2026-07-10T08:00:00Z',
    results: [
      { id: 31, testCode: 'CRP', label: 'C-reactive protein', reportedValue: 4.2, reportedUnit: 'mg/L', canonicalValue: 4.2, canonicalUnit: 'mg/L', referenceLower: null, referenceUpper: 5 },
    ],
  },
]

const trend = {
  patientProfileId: 41,
  testCode: 'CRP',
  label: 'C-reactive protein',
  canonicalUnit: 'mg/L',
  displayScale: 1,
  from: '2025-08-04',
  to: '2026-08-04',
  points: [
    { resultSetId: 3, resultSetVersion: 2, collectionDate: '2026-07-10', canonicalValue: 4.2, reportedValue: 4.2, reportedUnit: 'mg/L', referenceLower: null, referenceUpper: 5, editable: true },
  ],
}

describe('ClinicalPatientLabsView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('lists result sets and links to the editor', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/clinical/patients/41/labs/result-sets', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json(resultSets)
      }),
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/clinical/patients/:patientProfileId/labs', component: ClinicalPatientLabsView },
        { path: '/clinical/patients/:patientProfileId/labs/:resultSetId', component: { template: '<div />' } },
      ],
    })
    await router.push('/clinical/patients/41/labs?email=patient%40example.com')
    const wrapper = mount(ClinicalPatientLabsView, {
      global: { plugins: [createPinia(), i18n, router], stubs: { LineChart: true } },
    })
    await flushPromises()

    expect(seenUrl).toContain('/api/clinical/patients/41/labs/result-sets')
    expect(wrapper.text()).toContain('CRP')
    expect(wrapper.html()).toContain('href="/clinical/patients/41/labs/3?email=patient@example.com"')
    expect(wrapper.html()).toContain('href="/clinical/patients/41/labs/new?email=patient@example.com"')
  })

  it('loads the per-test trend when a test is selected', async () => {
    let trendUrl = ''
    let trendCalls = 0
    server.use(
      http.get('/api/clinical/patients/41/labs/result-sets', () => HttpResponse.json(resultSets)),
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/clinical/patients/41/labs/trends/CRP', ({ request }) => {
        trendCalls += 1
        trendUrl = request.url
        return HttpResponse.json(trend)
      }),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/clinical/patients/:patientProfileId/labs', component: ClinicalPatientLabsView },
        { path: '/clinical/patients/:patientProfileId/labs/new', component: { template: '<div />' } },
        { path: '/clinical/patients/:patientProfileId/labs/:resultSetId', component: { template: '<div />' } },
      ],
    })
    await router.push('/clinical/patients/41/labs')
    const wrapper = mount(ClinicalPatientLabsView, {
      global: { plugins: [createPinia(), i18n, router], stubs: { LineChart: true } },
    })
    await flushPromises()

    await wrapper.find('[data-testid="test-select"]').setValue('CRP')
    await flushPromises()
    expect(trendUrl).toContain('/api/clinical/patients/41/labs/trends/CRP')

    const callsAfterSelect = trendCalls
    await wrapper.find('[data-testid="apply-range"]').trigger('click')
    await flushPromises()
    expect(trendCalls).toBeGreaterThan(callsAfterSelect)
  })
})
