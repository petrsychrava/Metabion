import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalPatientRedFlagsView from '@/views/clinical/ClinicalPatientRedFlagsView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

function event(eventId: number, severity: string, current: boolean) {
  return {
    eventId,
    ruleKey: 'LAB_CRP_HIGH',
    severity,
    detectedAt: '2026-08-01T10:15:30Z',
    sourceType: 'LAB_RESULT_SET',
    sourceId: 91,
    current,
    supersededAt: current ? null : '2026-08-02T10:15:30Z',
    ruleVersion: 1,
  }
}

describe('ClinicalPatientRedFlagsView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders the current snapshot and paginates history with the cursor', async () => {
    const historyUrls: string[] = []
    server.use(
      http.get('/api/clinical/patients/41/red-flags/current', () =>
        HttpResponse.json({ highestSeverity: 'URGENT_REVIEW', flags: [event(701, 'URGENT_REVIEW', true)] }),
      ),
      http.get('/api/clinical/patients/41/red-flags/history', ({ request }) => {
        historyUrls.push(request.url)
        if (!request.url.includes('cursor=')) {
          return HttpResponse.json({ items: [event(701, 'URGENT_REVIEW', true)], nextCursor: 'abc' })
        }
        return HttpResponse.json({ items: [event(700, 'ROUTINE_REVIEW', false)], nextCursor: null })
      }),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/clinical/patients/:patientProfileId/red-flags', component: ClinicalPatientRedFlagsView }],
    })
    await router.push('/clinical/patients/41/red-flags')
    const wrapper = mount(ClinicalPatientRedFlagsView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.find('[data-testid="current-table"]').text()).toContain(en.redFlags.severity.URGENT_REVIEW)
    expect(wrapper.findAll('[data-testid="history-row"]')).toHaveLength(1)

    await wrapper.find('[data-testid="load-more"]').trigger('click')
    await flushPromises()
    expect(historyUrls[1]).toContain('cursor=abc')
    expect(wrapper.findAll('[data-testid="history-row"]')).toHaveLength(2)
    expect(wrapper.find('[data-testid="load-more"]').exists()).toBe(false)
  })

  it('clears loading and drops the in-flight history when the applied range is invalid', async () => {
    type HistoryPage = { items: ReturnType<typeof event>[]; nextCursor: string | null }
    let resolveHeld: (response: HttpResponse<HistoryPage>) => void = () => undefined
    server.use(
      http.get('/api/clinical/patients/41/red-flags/current', () =>
        HttpResponse.json({ highestSeverity: null, flags: [] }),
      ),
      http.get('/api/clinical/patients/41/red-flags/history', () =>
        new Promise<HttpResponse<HistoryPage>>((resolve) => {
          resolveHeld = resolve
        }),
      ),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/clinical/patients/:patientProfileId/red-flags', component: ClinicalPatientRedFlagsView }],
    })
    await router.push('/clinical/patients/41/red-flags')
    const wrapper = mount(ClinicalPatientRedFlagsView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.text()).toContain(en.common.loading)

    const dateInputs = wrapper.findAll('input[type="date"]')
    await dateInputs[0].setValue('2026-08-03')
    await dateInputs[1].setValue('2026-08-01')
    // The only rendered button at this point is the history Apply.
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.date_range_invalid)
    expect(wrapper.text()).not.toContain(en.common.loading)

    resolveHeld(HttpResponse.json({ items: [event(701, 'URGENT_REVIEW', true)], nextCursor: null }))
    await flushPromises()
    expect(wrapper.findAll('[data-testid="history-row"]')).toHaveLength(0)
  })

  it('re-enables load-more when an invalid range invalidates an in-flight page request', async () => {
    type HistoryPage = { items: ReturnType<typeof event>[]; nextCursor: string | null }
    let historyCalls = 0
    let resolvePage: (response: HttpResponse<HistoryPage>) => void = () => undefined
    server.use(
      http.get('/api/clinical/patients/41/red-flags/current', () =>
        HttpResponse.json({ highestSeverity: null, flags: [] }),
      ),
      http.get('/api/clinical/patients/41/red-flags/history', () => {
        historyCalls += 1
        if (historyCalls === 1) {
          return HttpResponse.json({ items: [event(701, 'URGENT_REVIEW', true)], nextCursor: 'abc' })
        }
        // The load-more request stays in flight until the test releases it.
        return new Promise<HttpResponse<HistoryPage>>((resolve) => {
          resolvePage = resolve
        })
      }),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/clinical/patients/:patientProfileId/red-flags', component: ClinicalPatientRedFlagsView }],
    })
    await router.push('/clinical/patients/41/red-flags')
    const wrapper = mount(ClinicalPatientRedFlagsView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    // Start a page load that never resolves on its own.
    await wrapper.find('[data-testid="load-more"]').trigger('click')
    expect(wrapper.find('[data-testid="load-more"]').attributes('disabled')).toBeDefined()

    const dateInputs = wrapper.findAll('input[type="date"]')
    await dateInputs[0].setValue('2026-08-03')
    await dateInputs[1].setValue('2026-08-01')
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain(en.errors.date_range_invalid)
    expect(wrapper.find('[data-testid="load-more"]').attributes('disabled')).toBeUndefined()

    // The invalidated page request resolves late and is dropped: still one row.
    resolvePage(HttpResponse.json({ items: [event(700, 'ROUTINE_REVIEW', false)], nextCursor: null }))
    await flushPromises()
    expect(wrapper.findAll('[data-testid="history-row"]')).toHaveLength(1)
  })
})
