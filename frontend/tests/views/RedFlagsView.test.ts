import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { server } from '../msw/server'
import RedFlagsView from '@/views/RedFlagsView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const currentSnapshot = {
  highestSeverity: 'URGENT_REVIEW',
  flags: [
    {
      eventId: 701,
      ruleKey: 'LAB_CRP_HIGH',
      severity: 'URGENT_REVIEW',
      detectedAt: '2026-08-01T10:15:30Z',
      sourceType: 'LAB_RESULT_SET',
      sourceId: 91,
      current: true,
      supersededAt: null,
    },
  ],
}

const historyEvent = {
  eventId: 700,
  ruleKey: 'SYM_ACTIVE_FLARE',
  severity: 'URGENT_REVIEW',
  detectedAt: '2026-07-30T08:00:00Z',
  sourceType: 'SYMPTOM_CHECK_IN',
  sourceId: 55,
  current: true,
  supersededAt: null,
}

function mountView() {
  return mount(RedFlagsView, { global: { plugins: [createPinia(), i18n] } })
}

describe('RedFlagsView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('shows current flags and history rows with localized labels', async () => {
    server.use(
      http.get('/api/red-flags/current', () => HttpResponse.json(currentSnapshot)),
      http.get('/api/red-flags/history', () => HttpResponse.json({ items: [historyEvent], nextCursor: null })),
    )
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain(en.redFlags.rules.LAB_CRP_HIGH)
    expect(wrapper.text()).toContain(en.redFlags.rules.SYM_ACTIVE_FLARE)
    expect(wrapper.text()).toContain(en.redFlags.sourceType.SYMPTOM_CHECK_IN)
    expect(wrapper.text()).toContain(en.redFlags.statusCurrent)
    expect(wrapper.text()).not.toContain(en.redFlags.noCurrent)
  })

  it('falls back to the raw rule key for unknown rules', async () => {
    server.use(
      http.get('/api/red-flags/current', () => HttpResponse.json({ highestSeverity: null, flags: [] })),
      http.get('/api/red-flags/history', () =>
        HttpResponse.json({ items: [{ ...historyEvent, ruleKey: 'LAB_FUTURE_RULE' }], nextCursor: null }),
      ),
    )
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('LAB_FUTURE_RULE')
    expect(wrapper.text()).toContain(en.redFlags.noCurrent)
  })

  it('labels superseded history entries as superseded', async () => {
    server.use(
      http.get('/api/red-flags/current', () => HttpResponse.json({ highestSeverity: null, flags: [] })),
      http.get('/api/red-flags/history', () =>
        HttpResponse.json({
          items: [{ ...historyEvent, current: false, supersededAt: '2026-07-31T08:00:00Z' }],
          nextCursor: null,
        }),
      ),
    )
    const wrapper = mountView()
    await flushPromises()
    const history = wrapper.find('[data-testid="history-table"]').text()
    expect(history).toContain(en.redFlags.statusSuperseded)
    expect(history).not.toContain(en.redFlags.statusCurrent)
  })

  it('blocks a range over 370 days without calling the history API again', async () => {
    let historyCalls = 0
    server.use(
      http.get('/api/red-flags/current', () => HttpResponse.json({ highestSeverity: null, flags: [] })),
      http.get('/api/red-flags/history', () => {
        historyCalls += 1
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
    )
    const wrapper = mountView()
    await flushPromises()
    expect(historyCalls).toBe(1)

    const [fromInput, toInput] = wrapper.findAll('input[type="date"]')
    await fromInput.setValue('2024-01-01')
    await toInput.setValue('2025-01-06')
    await wrapper.find('[data-testid="apply-history"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain(en.errors.date_range_too_long)
    expect(historyCalls).toBe(1)
  })

  it('applies the severity filter to history requests', async () => {
    const capturedUrls: string[] = []
    server.use(
      http.get('/api/red-flags/current', () => HttpResponse.json({ highestSeverity: null, flags: [] })),
      http.get('/api/red-flags/history', ({ request }) => {
        capturedUrls.push(request.url)
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
    )
    const wrapper = mountView()
    await flushPromises()
    expect(new URL(capturedUrls[0]).searchParams.has('severity')).toBe(false)

    await wrapper.find('[data-testid="severity-filter"]').setValue('EMERGENCY')
    await wrapper.find('[data-testid="apply-history"]').trigger('click')
    await flushPromises()

    expect(new URL(capturedUrls[1]).searchParams.get('severity')).toBe('EMERGENCY')
  })

  it('appends the next page on Load more and hides the button at the end', async () => {
    server.use(
      http.get('/api/red-flags/current', () => HttpResponse.json({ highestSeverity: null, flags: [] })),
      http.get('/api/red-flags/history', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor')
        if (!cursor) {
          return HttpResponse.json({ items: [historyEvent], nextCursor: 'cursor-2' })
        }
        return HttpResponse.json({
          items: [{ ...historyEvent, eventId: 699, ruleKey: 'LAB_CRP_ELEVATED' }],
          nextCursor: null,
        })
      }),
    )
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-testid="history-table"]').findAll('tbody tr')).toHaveLength(1)
    const loadMore = wrapper.find('[data-testid="load-more"]')
    expect(loadMore.exists()).toBe(true)

    await loadMore.trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="history-table"]').findAll('tbody tr')).toHaveLength(2)
    expect(wrapper.find('[data-testid="load-more"]').exists()).toBe(false)
  })

  it('blocks a 370-day range, matching the history endpoint limit', async () => {
    let historyCalls = 0
    server.use(
      http.get('/api/red-flags/current', () => HttpResponse.json({ highestSeverity: null, flags: [] })),
      http.get('/api/red-flags/history', () => {
        historyCalls += 1
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
    )
    const wrapper = mountView()
    await flushPromises()
    expect(historyCalls).toBe(1)

    const [fromInput, toInput] = wrapper.findAll('input[type="date"]')
    await fromInput.setValue('2025-01-01')
    await toInput.setValue('2026-01-06')
    await wrapper.find('[data-testid="apply-history"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain(en.errors.date_range_too_long)
    expect(historyCalls).toBe(1)
  })

  it('keeps Load more bound to the applied filters', async () => {
    const capturedUrls: string[] = []
    server.use(
      http.get('/api/red-flags/current', () => HttpResponse.json({ highestSeverity: null, flags: [] })),
      http.get('/api/red-flags/history', ({ request }) => {
        capturedUrls.push(request.url)
        const cursor = new URL(request.url).searchParams.get('cursor')
        return cursor
          ? HttpResponse.json({ items: [], nextCursor: null })
          : HttpResponse.json({ items: [historyEvent], nextCursor: 'cursor-2' })
      }),
    )
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[data-testid="severity-filter"]').setValue('EMERGENCY')
    await wrapper.find('[data-testid="load-more"]').trigger('click')
    await flushPromises()

    const url = new URL(capturedUrls[1])
    expect(url.searchParams.get('cursor')).toBe('cursor-2')
    expect(url.searchParams.has('severity')).toBe(false)
  })

  it('binds the cursor to the request filters even when controls change mid-flight', async () => {
    const capturedUrls: string[] = []
    let release!: () => void
    server.use(
      http.get('/api/red-flags/current', () => HttpResponse.json({ highestSeverity: null, flags: [] })),
      http.get('/api/red-flags/history', ({ request }) => {
        capturedUrls.push(request.url)
        if (capturedUrls.length === 1) {
          return new Promise<Response>((resolve) => {
            release = () => resolve(HttpResponse.json({ items: [historyEvent], nextCursor: 'cursor-2' }))
          })
        }
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
    )
    const wrapper = mountView()
    await vi.waitFor(() => expect(capturedUrls.length).toBe(1))

    // The user edits the severity while the first load is still in flight.
    await wrapper.find('[data-testid="severity-filter"]').setValue('EMERGENCY')
    release()
    await flushPromises()

    await wrapper.find('[data-testid="load-more"]').trigger('click')
    await flushPromises()

    const url = new URL(capturedUrls[1])
    expect(url.searchParams.get('cursor')).toBe('cursor-2')
    expect(url.searchParams.has('severity')).toBe(false)
  })

  it('ignores a stale history response that finishes after a newer one', async () => {
    const releases: Array<() => void> = []
    server.use(
      http.get('/api/red-flags/current', () => HttpResponse.json({ highestSeverity: null, flags: [] })),
      http.get('/api/red-flags/history', ({ request }) => {
        const filtered = new URL(request.url).searchParams.has('severity')
        const body = {
          items: [{ ...historyEvent, ruleKey: filtered ? 'LAB_CRP_ELEVATED' : 'SYM_ACTIVE_FLARE' }],
          nextCursor: null,
        }
        return new Promise<Response>((resolve) => {
          releases.push(() => resolve(HttpResponse.json(body)))
        })
      }),
    )
    const wrapper = mountView()
    await vi.waitFor(() => expect(releases.length).toBe(1))

    // Apply a severity filter before the initial load finishes.
    await wrapper.find('[data-testid="severity-filter"]').setValue('EMERGENCY')
    await wrapper.find('[data-testid="apply-history"]').trigger('click')
    await vi.waitFor(() => expect(releases.length).toBe(2))

    // Complete the newer request first, then the stale one.
    releases[1]()
    await flushPromises()
    releases[0]()
    await flushPromises()

    const table = wrapper.find('[data-testid="history-table"]')
    expect(table.findAll('tbody tr')).toHaveLength(1)
    expect(table.text()).toContain(en.redFlags.rules.LAB_CRP_ELEVATED)
    expect(table.text()).not.toContain(en.redFlags.rules.SYM_ACTIVE_FLARE)
  })

  it('shows an error instead of the empty state when the snapshot load fails', async () => {
    server.use(
      http.get('/api/red-flags/current', () => new HttpResponse(null, { status: 500 })),
      http.get('/api/red-flags/history', () => HttpResponse.json({ items: [], nextCursor: null })),
    )
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain(en.redFlags.currentLoadFailed)
    expect(wrapper.text()).not.toContain(en.redFlags.noCurrent)
    expect(wrapper.find('[data-testid="current-table"]').exists()).toBe(false)
  })

  it('shows no empty-state claim while the initial snapshot load is in flight', async () => {
    server.use(
      http.get('/api/red-flags/current', async () => {
        await new Promise<void>(() => {})
        return HttpResponse.json({ highestSeverity: null, flags: [] })
      }),
      http.get('/api/red-flags/history', () => HttpResponse.json({ items: [], nextCursor: null })),
    )
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).not.toContain(en.redFlags.noCurrent)
    expect(wrapper.text()).not.toContain(en.redFlags.currentLoadFailed)
    expect(wrapper.find('[data-testid="current-table"]').exists()).toBe(false)
  })

  it('shows the empty state only after a snapshot loads with zero flags', async () => {
    server.use(
      http.get('/api/red-flags/current', () => HttpResponse.json({ highestSeverity: null, flags: [] })),
      http.get('/api/red-flags/history', () => HttpResponse.json({ items: [], nextCursor: null })),
    )
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain(en.redFlags.noCurrent)
    expect(wrapper.text()).not.toContain(en.redFlags.currentLoadFailed)
  })
})
