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

  it('redirects authenticated users away from /login', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'p@example.com', roles: ['PATIENT'] })))
    const router = makeRouter()
    await router.push('/login')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('redirects coordinator staff to /staff-notice', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'c@example.com', roles: ['COORDINATOR'] })))
    const router = makeRouter()
    await router.push('/diet-logs')
    expect(router.currentRoute.value.path).toBe('/staff-notice')
  })

  it('redirects clinical experts from patient routes to /clinical', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'd@example.com', roles: ['PHYSICIAN'] })))
    const router = makeRouter()
    await router.push('/diet-logs')
    expect(router.currentRoute.value.path).toBe('/clinical')
  })

  it('lets clinical experts into the clinical area', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'n@example.com', roles: ['NUTRITION_SPECIALIST'] })))
    const router = makeRouter()
    await router.push('/clinical')
    expect(router.currentRoute.value.path).toBe('/clinical')
  })

  it('lets admins into the clinical area', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'a@example.com', roles: ['ADMIN'] })))
    const router = makeRouter()
    await router.push('/clinical')
    expect(router.currentRoute.value.path).toBe('/clinical')
  })

  it('keeps patients out of the clinical area', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'p@example.com', roles: ['PATIENT'] })))
    const router = makeRouter()
    await router.push('/clinical')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('keeps coordinators out of the clinical area', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'c@example.com', roles: ['COORDINATOR'] })))
    const router = makeRouter()
    await router.push('/clinical')
    expect(router.currentRoute.value.path).toBe('/staff-notice')
  })

  it('sends experts away from /login to /clinical', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'd@example.com', roles: ['PHYSICIAN'] })))
    const router = makeRouter()
    await router.push('/login')
    expect(router.currentRoute.value.path).toBe('/clinical')
  })

  it('preserves the query when the workspace index redirects to check-ins', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'd@example.com', roles: ['PHYSICIAN'] })))
    const router = makeRouter()
    await router.push('/clinical/patients/41?email=x%40example.com')
    expect(router.currentRoute.value.path).toBe('/clinical/patients/41/check-ins')
    expect(router.currentRoute.value.query.email).toBe('x@example.com')
  })
})
