import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import { server } from '../msw/server'
import AppShell from '@/components/AppShell.vue'
import en from '@/i18n/en.json'
import type { PatientRedFlagSnapshot } from '@/types/api'

const urgentSnapshot: PatientRedFlagSnapshot = {
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

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  configurable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }),
})

function makeI18n() {
  return createI18n({ legacy: false, locale: 'en', messages: { en } })
}

function makeRouter() {
  const stub = { template: '<div />' }
  return createRouter({
    history: createMemoryHistory(),
    routes: ['/', '/diet-logs', '/check-ins', '/trends', '/labs', '/red-flags', '/onboarding', '/education', '/account', '/login']
      .map((path) => ({ path, component: stub })),
  })
}

describe('AppShell', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    document.documentElement.classList.remove('dark')
  })

  it('persists the chosen locale through the account API', async () => {
    let putBody: unknown
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/account/preferences/language', async ({ request }) => {
        putBody = await request.json()
        return HttpResponse.json({ status: 'ok' })
      }),
    )
    const router = makeRouter()
    await router.push('/')
    const wrapper = mount(AppShell, { global: { plugins: [createPinia(), makeI18n(), router] } })
    await wrapper.find('select[aria-label="Language"]').setValue('cs')
    await flushPromises()
    expect(putBody).toEqual({ language: 'CS' })
  })

  it('persists the chosen theme through the account API', async () => {
    let putBody: unknown
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/account/preferences/theme', async ({ request }) => {
        putBody = await request.json()
        return HttpResponse.json({ status: 'ok' })
      }),
    )
    const router = makeRouter()
    await router.push('/')
    const wrapper = mount(AppShell, { global: { plugins: [createPinia(), makeI18n(), router] } })
    await wrapper.find('select[aria-label="Theme"]').setValue('DARK')
    await flushPromises()
    expect(putBody).toEqual({ theme: 'DARK' })
    expect(localStorage.getItem('metabion.theme')).toBe('DARK')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('navigates to login even when the logout request fails', async () => {
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/auth/logout', () => HttpResponse.json({ error: 'request_failed' }, { status: 500 })),
    )
    const router = makeRouter()
    await router.push('/')
    const wrapper = mount(AppShell, { global: { plugins: [createPinia(), makeI18n(), router] } })
    // The shell's only button is the logout button.
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('shows the urgent banner outside the dashboard and red-flags pages', async () => {
    server.use(http.get('/api/red-flags/current', () => HttpResponse.json(urgentSnapshot)))
    const router = makeRouter()
    await router.push('/labs')
    const wrapper = mount(AppShell, { global: { plugins: [createPinia(), makeI18n(), router] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="red-flag-banner"]').exists()).toBe(true)
  })

  it('hides the shell banner on the dashboard and on /red-flags', async () => {
    server.use(http.get('/api/red-flags/current', () => HttpResponse.json(urgentSnapshot)))
    for (const path of ['/', '/red-flags']) {
      setActivePinia(createPinia())
      const router = makeRouter()
      await router.push(path)
      const wrapper = mount(AppShell, { global: { plugins: [createPinia(), makeI18n(), router] } })
      await flushPromises()
      expect(wrapper.find('[data-testid="red-flag-banner"]').exists()).toBe(false)
      wrapper.unmount()
    }
  })

  it('does not show the shell banner for routine-only flags', async () => {
    server.use(
      http.get('/api/red-flags/current', () =>
        HttpResponse.json({ ...urgentSnapshot, highestSeverity: 'ROUTINE_REVIEW' }),
      ),
    )
    const router = makeRouter()
    await router.push('/labs')
    const wrapper = mount(AppShell, { global: { plugins: [createPinia(), makeI18n(), router] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="red-flag-banner"]').exists()).toBe(false)
  })

  it('refreshes the current snapshot on mount and renders the red-flags nav link', async () => {
    let currentCalls = 0
    server.use(
      http.get('/api/red-flags/current', () => {
        currentCalls += 1
        return HttpResponse.json({ highestSeverity: null, flags: [] })
      }),
    )
    const router = makeRouter()
    await router.push('/')
    const wrapper = mount(AppShell, { global: { plugins: [createPinia(), makeI18n(), router] } })
    await flushPromises()
    expect(currentCalls).toBe(1)
    expect(wrapper.html()).toContain('href="/red-flags"')
    expect(wrapper.text()).toContain(en.nav.redFlags)
  })
})
