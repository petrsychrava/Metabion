import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { server } from '../msw/server'
import LabResultSetsView from '@/views/LabResultSetsView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const resultSet = {
  id: 5,
  version: 1,
  patientProfileId: 3,
  collectionDate: '2026-07-20',
  notes: null,
  source: 'MANUAL',
  confirmationStatus: 'UNCONFIRMED',
  createdByCurrentPatient: true,
  createdAt: '2026-07-20T10:00:00Z',
  updatedAt: '2026-07-20T10:00:00Z',
  results: [
    {
      id: 11,
      testCode: 'CRP',
      label: 'CRP',
      reportedValue: 12,
      reportedUnit: 'mg/L',
      canonicalValue: 12,
      canonicalUnit: 'mg/L',
      referenceLower: 0,
      referenceUpper: 5,
    },
  ],
}

function mountView() {
  return mount(LabResultSetsView, {
    global: {
      plugins: [createPinia(), i18n],
      stubs: { RouterLink: { template: '<a><slot /></a>' } },
    },
  })
}

describe('LabResultSetsView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('refreshes the red-flag snapshot after a successful removal', async () => {
    let redFlagRefreshes = 0
    server.use(
      http.get('/api/lab-result-sets', () => HttpResponse.json([resultSet])),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/lab-result-sets/:id/removal', () => HttpResponse.json({ status: 'removed' })),
      http.get('/api/red-flags/current', () => {
        redFlagRefreshes += 1
        return HttpResponse.json({ highestSeverity: null, flags: [] })
      }),
    )
    const wrapper = mountView()
    await flushPromises()
    expect(redFlagRefreshes).toBe(0)

    const requestButton = wrapper.findAll('button').find((b) => b.text() === en.labs.requestRemoval)
    await requestButton!.trigger('click')
    const confirmButton = wrapper.findAll('button').find((b) => b.text() === en.account.confirm)
    await confirmButton!.trigger('click')
    await flushPromises()

    expect(redFlagRefreshes).toBe(1)
    expect(wrapper.text()).toContain(en.labs.removalRequested)
  })

  it('does not refresh the red-flag snapshot when the removal fails', async () => {
    let redFlagRefreshes = 0
    server.use(
      http.get('/api/lab-result-sets', () => HttpResponse.json([resultSet])),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/lab-result-sets/:id/removal', () => new HttpResponse(null, { status: 500 })),
      http.get('/api/red-flags/current', () => {
        redFlagRefreshes += 1
        return HttpResponse.json({ highestSeverity: null, flags: [] })
      }),
    )
    const wrapper = mountView()
    await flushPromises()

    const requestButton = wrapper.findAll('button').find((b) => b.text() === en.labs.requestRemoval)
    await requestButton!.trigger('click')
    const confirmButton = wrapper.findAll('button').find((b) => b.text() === en.account.confirm)
    await confirmButton!.trigger('click')
    await flushPromises()

    expect(redFlagRefreshes).toBe(0)
  })
})
