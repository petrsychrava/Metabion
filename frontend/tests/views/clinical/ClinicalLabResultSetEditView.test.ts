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
})
