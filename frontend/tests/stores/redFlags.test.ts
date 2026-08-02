import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { server } from '../msw/server'
import { useRedFlagsStore } from '@/stores/redFlags'

const snapshot = {
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

describe('redFlags store', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('refreshCurrent stores the snapshot', async () => {
    server.use(http.get('/api/red-flags/current', () => HttpResponse.json(snapshot)))
    const store = useRedFlagsStore()
    await store.refreshCurrent()
    expect(store.snapshot).toEqual(snapshot)
    expect(store.loadFailed).toBe(false)
  })

  it('refreshCurrent sets loadFailed instead of throwing', async () => {
    server.use(http.get('/api/red-flags/current', () => new HttpResponse(null, { status: 500 })))
    const store = useRedFlagsStore()
    await store.refreshCurrent()
    expect(store.loadFailed).toBe(true)
    expect(store.snapshot).toBeNull()
  })

  it('skips a second refresh while one is in flight', async () => {
    let calls = 0
    let release!: () => void
    server.use(
      http.get('/api/red-flags/current', async () => {
        calls += 1
        await new Promise<void>((resolve) => { release = resolve })
        return HttpResponse.json(snapshot)
      }),
    )
    const store = useRedFlagsStore()
    const first = store.refreshCurrent()
    const second = store.refreshCurrent()
    // Wait until the MSW handler has actually started before releasing it.
    await vi.waitFor(() => expect(calls).toBe(1))
    release()
    await Promise.all([first, second])
    expect(calls).toBe(1)
  })

  it('discards a refresh result when clear() runs while it is in flight', async () => {
    let calls = 0
    let release!: () => void
    server.use(
      http.get('/api/red-flags/current', async () => {
        calls += 1
        await new Promise<void>((resolve) => { release = resolve })
        return HttpResponse.json(snapshot)
      }),
    )
    const store = useRedFlagsStore()
    const pending = store.refreshCurrent()
    // Wait until the MSW handler has actually started before clearing/releasing.
    await vi.waitFor(() => expect(calls).toBe(1))
    store.clear()
    release()
    await pending
    expect(store.snapshot).toBeNull()
    expect(store.loading).toBe(false)
    expect(store.loadFailed).toBe(false)
  })

  it('clear resets all state', async () => {
    server.use(http.get('/api/red-flags/current', () => HttpResponse.json(snapshot)))
    const store = useRedFlagsStore()
    await store.refreshCurrent()
    store.clear()
    expect(store.snapshot).toBeNull()
    expect(store.loadFailed).toBe(false)
    expect(store.loading).toBe(false)
  })
})
