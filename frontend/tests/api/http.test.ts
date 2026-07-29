import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../msw/server'
import { ApiError, apiFetch, resetCsrfToken } from '@/api/http'

describe('apiFetch', () => {
  it('returns parsed JSON for GET', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'p@example.com', roles: ['PATIENT'] })))
    const me = await apiFetch<{ email: string; roles: string[] }>('/api/auth/me')
    expect(me.email).toBe('p@example.com')
  })

  it('bootstraps CSRF token once and sends it on mutations', async () => {
    let csrfCalls = 0
    let seenToken: string | null = null
    server.use(
      http.get('/api/csrf', () => {
        csrfCalls++
        return HttpResponse.json({ token: 'tok-1', headerName: 'X-XSRF-TOKEN' })
      }),
      http.post('/api/example', ({ request }) => {
        seenToken = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json({ status: 'ok' })
      }),
    )
    await apiFetch('/api/example', { method: 'POST', body: { a: 1 } })
    await apiFetch('/api/example', { method: 'POST', body: { a: 2 } })
    expect(seenToken).toBe('tok-1')
    expect(csrfCalls).toBe(1)
  })

  it('skips CSRF when csrf:false', async () => {
    server.use(
      http.post('/api/auth/login', ({ request }) => {
        expect(request.headers.get('X-XSRF-TOKEN')).toBeNull()
        return HttpResponse.json({ status: 'AUTHENTICATED', email: 'p@example.com', roles: ['PATIENT'], challengeId: null, methods: null })
      }),
    )
    const res = await apiFetch<{ status: string }>('/api/auth/login', { method: 'POST', body: { email: 'p@example.com', password: 'secret' }, csrf: false })
    expect(res.status).toBe('AUTHENTICATED')
  })

  it('maps 400 validation body to ApiError with fields', async () => {
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/x', () => HttpResponse.json({ error: 'validation_failed', fields: { email: 'must be valid' } }, { status: 400 })),
    )
    const err = (await apiFetch('/api/x', { method: 'POST', body: {} }).catch((e) => e)) as ApiError
    expect(err).toBeInstanceOf(ApiError)
    expect(err.status).toBe(400)
    expect(err.code).toBe('validation_failed')
    expect(err.fields).toEqual({ email: 'must be valid' })
  })

  it('maps 401 with empty body to ApiError("unauthorized")', async () => {
    server.use(http.get('/api/auth/me', () => new HttpResponse(null, { status: 401 })))
    const err = (await apiFetch('/api/auth/me').catch((e) => e)) as ApiError
    expect(err).toBeInstanceOf(ApiError)
    expect(err.status).toBe(401)
    expect(err.code).toBe('unauthorized')
  })

  it('invokes the registered unauthorized handler on 401', async () => {
    const { setUnauthorizedHandler } = await import('@/api/http')
    let called = 0
    setUnauthorizedHandler(() => { called++ })
    server.use(http.get('/api/auth/me', () => new HttpResponse(null, { status: 401 })))
    await apiFetch('/api/auth/me').catch(() => undefined)
    expect(called).toBe(1)
    setUnauthorizedHandler(null)
  })

  it('invokes the unauthorized handler when the CSRF bootstrap returns 401', async () => {
    const { setUnauthorizedHandler } = await import('@/api/http')
    resetCsrfToken()
    let called = 0
    setUnauthorizedHandler(() => { called++ })
    server.use(
      http.get('/api/csrf', () => new HttpResponse(null, { status: 401 })),
      http.post('/api/x', () => HttpResponse.json({ status: 'ok' })),
    )
    const err = (await apiFetch('/api/x', { method: 'POST', body: {} }).catch((e) => e)) as ApiError
    expect(err).toBeInstanceOf(ApiError)
    expect(err.status).toBe(401)
    expect(called).toBe(1)
    setUnauthorizedHandler(null)
    resetCsrfToken()
  })

  it('sends FormData without Content-Type override', async () => {
    let contentType: string | null = null
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/diet-log-photos/uploads', ({ request }) => {
        contentType = request.headers.get('Content-Type')
        return HttpResponse.json({ uploadId: 7, originalFilename: 'a.jpg', contentType: 'image/jpeg', sizeBytes: 10, caption: null, contentUrl: '/api/diet-log-photos/7/content' })
      }),
    )
    await apiFetch('/api/diet-log-photos/uploads', { method: 'POST', formData: new FormData() })
    expect(contentType).toMatch(/^multipart\/form-data/)
  })
})
