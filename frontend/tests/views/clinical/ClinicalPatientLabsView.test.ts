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
    expect(wrapper.html()).toContain('href="/clinical/patients/41/labs/3"')
    expect(wrapper.html()).toContain('href="/clinical/patients/41/labs/new"')
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

  it('drops a stale trend response when the selection changes mid-flight', async () => {
    const twoTests = [
      catalog[0],
      { code: 'HGB', label: 'Hemoglobin', category: 'HEMATOLOGY', canonicalUnit: 'g/dL', displayScale: 1, allowedUnits: ['g/dL'] },
    ]
    let resolveFirst: (response: HttpResponse<typeof trend>) => void = () => undefined
    server.use(
      http.get('/api/clinical/patients/41/labs/result-sets', () => HttpResponse.json([])),
      http.get('/api/lab-tests', () => HttpResponse.json(twoTests)),
      // The CRP request never resolves until the test releases it.
      http.get('/api/clinical/patients/41/labs/trends/CRP', () =>
        new Promise<HttpResponse<typeof trend>>((resolve) => {
          resolveFirst = resolve
        }),
      ),
      http.get('/api/clinical/patients/41/labs/trends/HGB', () =>
        HttpResponse.json({ ...trend, testCode: 'HGB', label: 'Hemoglobin', canonicalUnit: 'g/dL' }),
      ),
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

    // Start the CRP request (stays in flight), then switch to HGB, which resolves.
    await wrapper.find('[data-testid="test-select"]').setValue('CRP')
    await wrapper.find('[data-testid="test-select"]').setValue('HGB')
    await flushPromises()
    expect(wrapper.text()).toContain('Hemoglobin (g/dL)')

    // The stale CRP response lands now and must not overwrite the HGB chart.
    resolveFirst(HttpResponse.json(trend))
    await flushPromises()
    expect(wrapper.text()).toContain('Hemoglobin (g/dL)')
    expect(wrapper.text()).not.toContain('C-reactive protein (mg/L)')
  })

  it('clears the list loading state and drops the in-flight response when the applied range is invalid', async () => {
    let resolveHeld: (response: HttpResponse<typeof resultSets>) => void = () => undefined
    server.use(
      http.get('/api/clinical/patients/41/labs/result-sets', () =>
        new Promise<HttpResponse<typeof resultSets>>((resolve) => {
          resolveHeld = resolve
        }),
      ),
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
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
    expect(wrapper.text()).toContain(en.common.loading)

    const dateInputs = wrapper.findAll('input[type="date"]')
    await dateInputs[0].setValue('2026-08-03')
    await dateInputs[1].setValue('2026-08-01')
    await wrapper.find('[data-testid="apply-range"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.date_range_invalid)
    expect(wrapper.text()).not.toContain(en.common.loading)

    resolveHeld(HttpResponse.json(resultSets))
    await flushPromises()
    expect(wrapper.find('[data-testid="resultsets-table"] tbody').findAll('tr')).toHaveLength(0)
  })

  it('clears the previous chart when a replacement trend request fails', async () => {
    const twoTests = [
      catalog[0],
      { code: 'HGB', label: 'Hemoglobin', category: 'HEMATOLOGY', canonicalUnit: 'g/dL', displayScale: 1, allowedUnits: ['g/dL'] },
    ]
    server.use(
      http.get('/api/clinical/patients/41/labs/result-sets', () => HttpResponse.json([])),
      http.get('/api/lab-tests', () => HttpResponse.json(twoTests)),
      http.get('/api/clinical/patients/41/labs/trends/CRP', () => HttpResponse.json(trend)),
      http.get('/api/clinical/patients/41/labs/trends/HGB', () =>
        HttpResponse.json({ error: 'request_failed' }, { status: 500 })),
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
    expect(wrapper.text()).toContain('C-reactive protein (mg/L)')

    await wrapper.find('[data-testid="test-select"]').setValue('HGB')
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.request_failed)
    expect(wrapper.text()).not.toContain('C-reactive protein (mg/L)')
  })

  it('clears a previous trend error after a replacement request succeeds', async () => {
    const twoTests = [
      catalog[0],
      { code: 'HGB', label: 'Hemoglobin', category: 'HEMATOLOGY', canonicalUnit: 'g/dL', displayScale: 1, allowedUnits: ['g/dL'] },
    ]
    server.use(
      http.get('/api/clinical/patients/41/labs/result-sets', () => HttpResponse.json([])),
      http.get('/api/lab-tests', () => HttpResponse.json(twoTests)),
      http.get('/api/clinical/patients/41/labs/trends/CRP', () =>
        HttpResponse.json({ error: 'request_failed' }, { status: 500 })),
      http.get('/api/clinical/patients/41/labs/trends/HGB', () =>
        HttpResponse.json({ ...trend, testCode: 'HGB', label: 'Hemoglobin', canonicalUnit: 'g/dL' })),
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
    expect(wrapper.text()).toContain(en.errors.request_failed)

    await wrapper.find('[data-testid="test-select"]').setValue('HGB')
    await flushPromises()
    expect(wrapper.text()).toContain('Hemoglobin (g/dL)')
    expect(wrapper.text()).not.toContain(en.errors.request_failed)
  })

  it('invalidates an in-flight trend as soon as Apply is clicked', async () => {
    let resolveStaleTrend: (response: HttpResponse<typeof trend>) => void = () => undefined
    let trendCalls = 0
    server.use(
      // The Apply-time list request never resolves, so loadList alone cannot
      // be what drops the stale trend — only the synchronous bump in apply() can.
      http.get('/api/clinical/patients/41/labs/result-sets', () =>
        new Promise<HttpResponse<never[]>>(() => undefined),
      ),
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/clinical/patients/41/labs/trends/CRP', () => {
        trendCalls += 1
        if (trendCalls === 1) {
          return new Promise<HttpResponse<typeof trend>>((resolve) => {
            resolveStaleTrend = resolve
          })
        }
        return HttpResponse.json({ ...trend, canonicalUnit: 'g/dL' })
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

    // Select CRP: the trend request stays in flight.
    await wrapper.find('[data-testid="test-select"]').setValue('CRP')
    // Apply starts the replacement trend (and the never-resolving list reload).
    await wrapper.find('[data-testid="apply-range"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('C-reactive protein (g/dL)')

    // The pre-Apply trend response lands now and must be dropped even though
    // the list reload is still pending.
    resolveStaleTrend(HttpResponse.json(trend))
    await flushPromises()
    expect(wrapper.text()).toContain('C-reactive protein (g/dL)')
    expect(wrapper.text()).not.toContain('C-reactive protein (mg/L)')
  })

  it('retains a catalog failure after the list load succeeds', async () => {
    server.use(
      http.get('/api/clinical/patients/41/labs/result-sets', () => HttpResponse.json(resultSets)),
      http.get('/api/lab-tests', () => HttpResponse.json({ error: 'request_failed' }, { status: 500 })),
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

    expect(wrapper.find('[data-testid="catalog-failed"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('CRP')
  })

  it('clears the previous rows when a replacement list request fails', async () => {
    let call = 0
    server.use(
      http.get('/api/clinical/patients/41/labs/result-sets', () => {
        call += 1
        if (call === 1) return HttpResponse.json(resultSets)
        return HttpResponse.json({ error: 'request_failed' }, { status: 500 })
      }),
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
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
    expect(wrapper.find('[data-testid="resultsets-table"] tbody').findAll('tr')).toHaveLength(1)

    await wrapper.find('[data-testid="apply-range"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.request_failed)
    expect(wrapper.find('[data-testid="resultsets-table"] tbody').findAll('tr')).toHaveLength(0)
  })
})
