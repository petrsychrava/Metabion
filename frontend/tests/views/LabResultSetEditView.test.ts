import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../msw/server'
import LabResultSetEditView from '@/views/LabResultSetEditView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const catalog = [
  { code: 'CRP', label: 'C-reactive protein', category: 'INFLAMMATION', canonicalUnit: 'mg/L', displayScale: 1, allowedUnits: ['mg/L'] },
]

const existing = {
  id: 3,
  version: 2,
  patientProfileId: 1,
  collectionDate: '2026-07-10',
  notes: 'note',
  source: 'MANUAL',
  confirmationStatus: 'UNCONFIRMED',
  createdByCurrentPatient: true,
  createdAt: '2026-07-10T08:00:00Z',
  updatedAt: '2026-07-10T08:00:00Z',
  results: [
    { id: 31, testCode: 'CRP', label: 'C-reactive protein', reportedValue: 4.2, reportedUnit: 'mg/L', canonicalValue: 4.2, canonicalUnit: 'mg/L', referenceLower: null, referenceUpper: 5 },
  ],
}

async function makeRouter(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/labs/new', component: LabResultSetEditView },
      { path: '/labs/:id', component: LabResultSetEditView },
    ],
  })
  await router.push(path)
  return router
}

describe('LabResultSetEditView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('creates a new result set without resultSetId/version', async () => {
    let received: Record<string, unknown> | null = null
    server.use(
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/lab-result-sets', async ({ request }) => {
        received = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ ...existing, id: 4 })
      }),
    )
    const router = await makeRouter('/labs/new')
    const wrapper = mount(LabResultSetEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('input[type="date"]').setValue('2026-07-20')
    await wrapper.find('[data-testid="add-result"]').trigger('click')
    await wrapper.find('[data-testid="result-value-0"]').setValue('3.1')
    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(received).not.toBeNull()
    expect(received!.resultSetId ?? null).toBeNull()
    expect(received!.version ?? null).toBeNull()
    expect(received!.collectionDate).toBe('2026-07-20')
    expect((received!.results as unknown[]).length).toBe(1)
  })

  it('sends the incremented version on a back-to-back save after a successful update', async () => {
    const receivedVersions: unknown[] = []
    server.use(
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/lab-result-sets/3', () => HttpResponse.json(existing)),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/lab-result-sets/3', async ({ request }) => {
        const body = (await request.json()) as { version: number }
        receivedVersions.push(body.version)
        return HttpResponse.json({ ...existing, version: body.version + 1 })
      }),
    )
    const router = await makeRouter('/labs/3')
    const wrapper = mount(LabResultSetEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(en.common.saved)

    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(receivedVersions).toEqual([2, 3])
    expect(wrapper.find('[data-testid="reload"]').exists()).toBe(false)
  })

  it('shows conflict message and reload button on 409', async () => {
    server.use(
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/lab-result-sets/3', () => HttpResponse.json(existing)),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/lab-result-sets/3', () => HttpResponse.json({ error: 'conflict' }, { status: 409 })),
    )
    const router = await makeRouter('/labs/3')
    const wrapper = mount(LabResultSetEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.find('input[type="date"]').element).toHaveProperty('value', '2026-07-10')

    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.conflict)
    expect(wrapper.find('[data-testid="reload"]').exists()).toBe(true)
  })

  it('refreshes the current red-flag snapshot after a successful update', async () => {
    let currentCalls = 0
    server.use(
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/lab-result-sets/3', () => HttpResponse.json(existing)),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/lab-result-sets/3', () => HttpResponse.json({ ...existing, version: 3 })),
      http.get('/api/red-flags/current', () => {
        currentCalls += 1
        return HttpResponse.json({ highestSeverity: null, flags: [] })
      }),
    )
    const router = await makeRouter('/labs/3')
    const wrapper = mount(LabResultSetEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(currentCalls).toBe(0)

    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain(en.common.saved)
    expect(currentCalls).toBe(1)
  })
})
