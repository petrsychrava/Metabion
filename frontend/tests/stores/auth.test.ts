import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { server } from '../msw/server'
import { useAuthStore } from '@/stores/auth'

describe('auth store', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('fetchMe sets authenticated identity', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'p@example.com', roles: ['PATIENT'] })))
    const auth = useAuthStore()
    await auth.fetchMe()
    expect(auth.status).toBe('authenticated')
    expect(auth.email).toBe('p@example.com')
    expect(auth.isPatient).toBe(true)
  })

  it('fetchMe maps 401 to anonymous', async () => {
    server.use(http.get('/api/auth/me', () => new HttpResponse(null, { status: 401 })))
    const auth = useAuthStore()
    await auth.fetchMe()
    expect(auth.status).toBe('anonymous')
    expect(auth.isAuthenticated).toBe(false)
  })

  it('login sets identity on AUTHENTICATED', async () => {
    server.use(http.post('/api/auth/login', () => HttpResponse.json({ status: 'AUTHENTICATED', email: 'p@example.com', roles: ['PATIENT'], challengeId: null, methods: null })))
    const auth = useAuthStore()
    const res = await auth.login('p@example.com', 'password-123')
    expect(res.status).toBe('AUTHENTICATED')
    expect(auth.status).toBe('authenticated')
    expect(auth.mfaRequired).toBe(false)
  })

  it('login flags mfaRequired without authenticating', async () => {
    server.use(http.post('/api/auth/login', () => HttpResponse.json({ status: 'MFA_REQUIRED', email: 'p@example.com', roles: ['PATIENT'], challengeId: 'ch-1', methods: ['TOTP'] })))
    const auth = useAuthStore()
    await auth.login('p@example.com', 'password-123')
    expect(auth.mfaRequired).toBe(true)
    expect(auth.status).not.toBe('authenticated')
  })

  it('logout resets state', async () => {
    server.use(
      http.get('/api/auth/me', () => HttpResponse.json({ email: 'p@example.com', roles: ['PATIENT'] })),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/auth/logout', () => new HttpResponse(null, { status: 200 })),
    )
    const auth = useAuthStore()
    await auth.fetchMe()
    await auth.logout()
    expect(auth.status).toBe('anonymous')
    expect(auth.email).toBeNull()
    expect(auth.roles).toEqual([])
  })
})
