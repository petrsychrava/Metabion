import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../msw/server'
import AccountProfileView from '@/views/AccountProfileView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const profile = {
  dateOfBirth: '1990-05-12',
  sex: 'FEMALE',
  countryRegion: 'CZ',
  timezone: 'Europe/Prague',
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/account/profile', component: AccountProfileView },
      { path: '/account/access-tokens', component: { template: '<div />' } },
    ],
  })
}

describe('AccountProfileView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('loads the profile into the form', async () => {
    server.use(
      http.get('/api/account/profile', () => HttpResponse.json(profile)),
    )
    const router = makeRouter()
    await router.push('/account/profile')
    const wrapper = mount(AccountProfileView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect((wrapper.find('input#dob').element as HTMLInputElement).value).toBe('1990-05-12')
    expect((wrapper.find('select#sex').element as HTMLSelectElement).value).toBe('FEMALE')
    expect((wrapper.find('input#country').element as HTMLInputElement).value).toBe('CZ')
    expect((wrapper.find('input#tz').element as HTMLInputElement).value).toBe('Europe/Prague')
  })

  it('shows an error and withholds the form when the profile fails to load', async () => {
    server.use(
      http.get('/api/account/profile', () => HttpResponse.json({ error: 'request_failed' }, { status: 500 })),
    )
    const router = makeRouter()
    await router.push('/account/profile')
    const wrapper = mount(AccountProfileView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.request_failed)
    expect(wrapper.find('form').exists()).toBe(false)
  })
})
