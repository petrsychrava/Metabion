import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../msw/server'
import { routes, installAuthGuard } from '@/router/index'

function makeRouter() {
  const router = createRouter({ history: createMemoryHistory(), routes })
  installAuthGuard(router)
  return router
}

describe('router auth guard', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('redirects anonymous users from protected routes to /login', async () => {
    server.use(http.get('/api/auth/me', () => new HttpResponse(null, { status: 401 })))
    const router = makeRouter()
    await router.push('/diet-logs')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/diet-logs')
  })

  it('lets patients through to protected routes', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'p@example.com', roles: ['PATIENT'] })))
    const router = makeRouter()
    await router.push('/diet-logs')
    expect(router.currentRoute.value.path).toBe('/diet-logs')
  })

  it('redirects non-patient staff to /staff-notice', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 's@example.com', roles: ['PHYSICIAN'] })))
    const router = makeRouter()
    await router.push('/diet-logs')
    expect(router.currentRoute.value.path).toBe('/staff-notice')
  })

  it('redirects authenticated users away from /login', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'p@example.com', roles: ['PATIENT'] })))
    const router = makeRouter()
    await router.push('/login')
    expect(router.currentRoute.value.path).toBe('/')
  })
})
