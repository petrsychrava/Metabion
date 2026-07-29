import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import { server } from '../msw/server'
import AppShell from '@/components/AppShell.vue'
import en from '@/i18n/en.json'

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
    routes: ['/', '/diet-logs', '/check-ins', '/trends', '/labs', '/onboarding', '/education', '/account', '/login']
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
    await wrapper.find('select').setValue('cs')
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
})
