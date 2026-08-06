import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalLabResultSetEditView from '@/views/clinical/ClinicalLabResultSetEditView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const catalog = [
  { code: 'CRP', label: 'C-reactive protein', category: 'INFLAMMATION', canonicalUnit: 'mg/L', displayScale: 1, allowedUnits: ['mg/L'] },
]

const catalogMulti = [
  ...catalog,
  { code: 'GLU', label: 'Glucose', category: 'METABOLIC', canonicalUnit: 'mmol/L', displayScale: 1, allowedUnits: ['mmol/L', 'mg/dL'] },
]

const existing = {
  id: 3,
  version: 2,
  patientProfileId: 41,
  collectionDate: '2026-07-10',
  notes: 'note',
  source: 'MANUAL',
  confirmationStatus: 'UNCONFIRMED',
  createdByCurrentPatient: false,
  createdAt: '2026-07-10T08:00:00Z',
  updatedAt: '2026-07-10T08:00:00Z',
  results: [
    { id: 31, testCode: 'CRP', label: 'C-reactive protein', reportedValue: 4.2, reportedUnit: 'mg/L', canonicalValue: 4.2, canonicalUnit: 'mg/L', referenceLower: null, referenceUpper: 5 },
  ],
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical/patients/:patientProfileId/labs', component: { template: '<div />' } },
      { path: '/clinical/patients/:patientProfileId/labs/new', component: ClinicalLabResultSetEditView },
      { path: '/clinical/patients/:patientProfileId/labs/:resultSetId', component: ClinicalLabResultSetEditView },
    ],
  })
}

describe('ClinicalLabResultSetEditView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('shows conflict message and reload button on 409', async () => {
    server.use(
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/clinical/patients/41/labs/result-sets/3', () => HttpResponse.json(existing)),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/clinical/patients/41/labs/result-sets/3', () =>
        HttpResponse.json({ error: 'conflict' }, { status: 409 })),
    )
    const router = makeRouter()
    await router.push('/clinical/patients/41/labs/3')
    const wrapper = mount(ClinicalLabResultSetEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.find('input[type="date"]').element).toHaveProperty('value', '2026-07-10')

    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.conflict)
    expect(wrapper.find('[data-testid="reload"]').exists()).toBe(true)
  })

  it('requests removal with a reason and returns to the labs tab', async () => {
    let received: unknown
    server.use(
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/clinical/patients/41/labs/result-sets/3', () => HttpResponse.json(existing)),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/clinical/patients/41/labs/result-sets/3/removal', async ({ request }) => {
        received = await request.json()
        return HttpResponse.json({ status: 'removed' })
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/patients/41/labs/3?email=patient%40example.com')
    const wrapper = mount(ClinicalLabResultSetEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="removal-reason"]').setValue('entered in error')
    await wrapper.find('[data-testid="remove"]').trigger('click')
    await flushPromises()

    expect(received).toEqual({ resultSetId: 3, version: 2, reason: 'entered in error' })
    expect(router.currentRoute.value.path).toBe('/clinical/patients/41/labs')
    expect(router.currentRoute.value.query.email).toBe('patient@example.com')
  })

  it('removes a result row via the per-row remove button', async () => {
    server.use(
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/clinical/patients/41/labs/result-sets/3', () => HttpResponse.json(existing)),
    )
    const router = makeRouter()
    await router.push('/clinical/patients/41/labs/3')
    const wrapper = mount(ClinicalLabResultSetEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.find('[data-testid="result-value-0"]').exists()).toBe(true)
    await wrapper.find('[data-testid="remove-result-0"]').trigger('click')
    expect(wrapper.find('[data-testid="result-value-0"]').exists()).toBe(false)
  })

  it('resets the row unit to the first allowed unit when the test changes', async () => {
    server.use(
      http.get('/api/lab-tests', () => HttpResponse.json(catalogMulti)),
    )
    const router = makeRouter()
    await router.push('/clinical/patients/41/labs/new')
    const wrapper = mount(ClinicalLabResultSetEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="add-result"]').trigger('click')
    const selects = wrapper.findAll('select')
    await selects[0].setValue('GLU')
    expect((selects[1].element as HTMLSelectElement).value).toBe('mmol/L')
  })

  it('sends a cleared value input as null in the save payload', async () => {
    let received: { results: { value: unknown }[] } | undefined
    server.use(
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/clinical/patients/41/labs/result-sets', async ({ request }) => {
        received = await request.json() as { results: { value: unknown }[] }
        return HttpResponse.json({ ...existing, id: 4 })
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/patients/41/labs/new')
    const wrapper = mount(ClinicalLabResultSetEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="add-result"]').trigger('click')
    await wrapper.find('[data-testid="result-value-0"]').setValue('')
    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(received?.results[0]?.value).toBeNull()
  })

  it('defaults the collection date to today for a new set', async () => {
    server.use(
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
    )
    const router = makeRouter()
    await router.push('/clinical/patients/41/labs/new')
    const wrapper = mount(ClinicalLabResultSetEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    const d = new Date()
    const today = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    expect(wrapper.find('input[type="date"]').element).toHaveProperty('value', today)
  })

  it('keeps the conflict prompt and reports the error when the conflict reload fails', async () => {
    let getCalls = 0
    server.use(
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/clinical/patients/41/labs/result-sets/3', () => {
        getCalls += 1
        // The initial load succeeds; the conflict-triggered reload fails.
        if (getCalls === 1) return HttpResponse.json(existing)
        return HttpResponse.json({ error: 'request_failed' }, { status: 500 })
      }),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/clinical/patients/41/labs/result-sets/3', () =>
        HttpResponse.json({ error: 'conflict' }, { status: 409 })),
    )
    const router = makeRouter()
    await router.push('/clinical/patients/41/labs/3')
    const wrapper = mount(ClinicalLabResultSetEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="reload"]').exists()).toBe(true)

    await wrapper.find('[data-testid="reload"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.request_failed)
    expect(wrapper.find('[data-testid="reload"]').exists()).toBe(true)
  })

  it('ignores a second save while a create is in flight', async () => {
    let postCalls = 0
    let resolveCreate: (response: HttpResponse<typeof existing>) => void = () => undefined
    server.use(
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/clinical/patients/41/labs/result-sets', () => {
        postCalls += 1
        return new Promise<HttpResponse<typeof existing>>((resolve) => {
          resolveCreate = resolve
        })
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/patients/41/labs/new')
    const wrapper = mount(ClinicalLabResultSetEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="save"]').trigger('click')
    await wrapper.find('[data-testid="save"]').trigger('click')
    resolveCreate(HttpResponse.json({ ...existing, id: 4 }))
    await flushPromises()

    expect(postCalls).toBe(1)
  })
})
