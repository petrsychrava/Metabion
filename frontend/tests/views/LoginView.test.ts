import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import { server } from '../msw/server'
import LoginView from '@/views/LoginView.vue'
import en from '@/i18n/en.json'

function makeI18n() {
  return createI18n({ legacy: false, locale: 'en', messages: { en } })
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: LoginView },
      { path: '/', component: { template: '<div />' }, meta: { requiresAuth: true } },
    ],
  })
}

describe('LoginView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('logs in and navigates home', async () => {
    server.use(
      http.post('/api/auth/login', () => HttpResponse.json({ status: 'AUTHENTICATED', email: 'p@example.com', roles: ['PATIENT'], challengeId: null, methods: null })),
      http.get('/api/auth/me', () => HttpResponse.json({ email: 'p@example.com', roles: ['PATIENT'] })),
    )
    const router = makeRouter()
    await router.push('/login')
    const wrapper = mount(LoginView, { global: { plugins: [createPinia(), makeI18n(), router] } })
    await wrapper.find('input[type="email"]').setValue('p@example.com')
    await wrapper.find('input[type="password"]').setValue('password-123')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('shows invalid_credentials error on 401', async () => {
    server.use(http.post('/api/auth/login', () => HttpResponse.json({ error: 'invalid_credentials' }, { status: 401 })))
    const router = makeRouter()
    await router.push('/login')
    const wrapper = mount(LoginView, { global: { plugins: [createPinia(), makeI18n(), router] } })
    await wrapper.find('input[type="email"]').setValue('p@example.com')
    await wrapper.find('input[type="password"]').setValue('wrong-password')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.invalid_credentials)
  })

  it('shows MFA placeholder when MFA_REQUIRED', async () => {
    server.use(http.post('/api/auth/login', () => HttpResponse.json({ status: 'MFA_REQUIRED', email: 'p@example.com', roles: ['PATIENT'], challengeId: 'c1', methods: ['TOTP'] })))
    const router = makeRouter()
    await router.push('/login')
    const wrapper = mount(LoginView, { global: { plugins: [createPinia(), makeI18n(), router] } })
    await wrapper.find('input[type="email"]').setValue('p@example.com')
    await wrapper.find('input[type="password"]').setValue('password-123')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(wrapper.text()).toContain(en.auth.mfaRequired)
    expect(router.currentRoute.value.path).toBe('/login')
  })
})
