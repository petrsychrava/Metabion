import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../msw/server'
import { redFlagApi } from '@/api/redFlags'

describe('redFlagApi', () => {
  it('getCurrent requests the current snapshot', async () => {
    server.use(
      http.get('/api/red-flags/current', () =>
        HttpResponse.json({ highestSeverity: null, flags: [] }),
      ),
    )
    const snapshot = await redFlagApi.getCurrent()
    expect(snapshot).toEqual({ highestSeverity: null, flags: [] })
  })

  it('getHistory appends only the provided parameters', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/red-flags/history', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
    )
    await redFlagApi.getHistory({ from: '2026-07-01', to: '2026-07-31', severity: 'EMERGENCY', size: 50 })
    const url = new URL(capturedUrl)
    expect(url.searchParams.get('from')).toBe('2026-07-01')
    expect(url.searchParams.get('to')).toBe('2026-07-31')
    expect(url.searchParams.get('severity')).toBe('EMERGENCY')
    expect(url.searchParams.get('size')).toBe('50')
    expect(url.searchParams.has('cursor')).toBe(false)
  })

  it('getHistory forwards the cursor and sends no query string without params', async () => {
    const captured: string[] = []
    server.use(
      http.get('/api/red-flags/history', ({ request }) => {
        captured.push(request.url)
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
    )
    await redFlagApi.getHistory({})
    await redFlagApi.getHistory({ cursor: 'opaque-cursor-1' })
    expect(new URL(captured[0]).search).toBe('')
    expect(new URL(captured[1]).searchParams.get('cursor')).toBe('opaque-cursor-1')
  })
})
