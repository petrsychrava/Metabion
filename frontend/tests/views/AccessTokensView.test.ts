import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { server } from '../msw/server'
import AccessTokensView from '@/views/AccessTokensView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const summary = {
  tokenId: 5,
  clientType: 'MCP_OTHER',
  displayLabel: 'My client',
  createdAt: '2026-07-01T10:00:00Z',
  expiresAt: '2026-08-01T10:00:00Z',
  lastUsedAt: null,
  scopes: ['patient:profile:read'],
}

describe('AccessTokensView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('lists existing tokens', async () => {
    server.use(http.get('/api/account/access-tokens', () => HttpResponse.json([summary])))
    const wrapper = mount(AccessTokensView, { global: { plugins: [createPinia(), i18n] } })
    await flushPromises()
    expect(wrapper.text()).toContain('My client')
    expect(wrapper.text()).toContain('patient:profile:read')
  })

  it('shows the plaintext token once after issuing', async () => {
    server.use(
      http.get('/api/account/access-tokens', () => HttpResponse.json([])),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/account/access-tokens', () =>
        HttpResponse.json({ tokenId: 6, plainToken: 'plain-secret-token', clientType: 'MCP_OTHER', displayLabel: 'New', expiresAt: '2026-08-01T10:00:00Z', scopes: ['patient:profile:read'] }),
      ),
    )
    const wrapper = mount(AccessTokensView, { global: { plugins: [createPinia(), i18n] } })
    await flushPromises()
    await wrapper.find('input[data-testid="display-label"]').setValue('New')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(wrapper.find('[data-testid="plain-token"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="plain-token"]').text()).toContain('plain-secret-token')
  })

  it('revokes a token after confirmation', async () => {
    let deleted = false
    server.use(
      http.get('/api/account/access-tokens', () => HttpResponse.json(deleted ? [] : [summary])),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.delete('/api/account/access-tokens/5', () => {
        deleted = true
        return new HttpResponse(null, { status: 200 })
      }),
    )
    const wrapper = mount(AccessTokensView, { global: { plugins: [createPinia(), i18n] } })
    await flushPromises()
    await wrapper.find('[data-testid="revoke-5"]').trigger('click')
    await flushPromises()
    // confirmation dialog appears; confirm it
    await wrapper.find('[data-testid="confirm-revoke"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('My client')
  })
})
