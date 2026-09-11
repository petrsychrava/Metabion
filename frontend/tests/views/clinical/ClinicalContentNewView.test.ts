import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalContentNewView from '@/views/clinical/ClinicalContentNewView.vue'
import en from '@/i18n/en.json'
import cs from '@/i18n/cs.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en, cs } })

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical/content/new', component: ClinicalContentNewView },
      { path: '/clinical/content/:moduleSlug/:version/edit', component: { template: '<div />' } },
    ],
  })
}

describe('ClinicalContentNewView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('creates the module and opens the editor', async () => {
    let received: unknown
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/modules', async ({ request }) => {
        received = await request.json()
        return HttpResponse.json({ moduleSlug: 'ibd-basics', version: 1 })
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/content/new')
    const wrapper = mount(ClinicalContentNewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="slug"]').setValue('ibd-basics')
    await wrapper.find('[data-testid="topic"]').setValue('IBD')
    await wrapper.find('[data-testid="english-title"]').setValue('IBD Basics')
    await wrapper.find('[data-testid="english-summary"]').setValue('Overview.')
    await wrapper.find('[data-testid="create"]').trigger('submit')
    await flushPromises()

    expect(received).toEqual({
      slug: 'ibd-basics',
      topic: 'IBD',
      sortOrder: 10,
      englishTitle: 'IBD Basics',
      englishSummary: 'Overview.',
      czechTitle: null,
      czechSummary: null,
    })
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/1/edit')
  })

  it('shows field errors from the server', async () => {
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/modules', () =>
        HttpResponse.json(
          { error: 'validation_failed', fields: { slug: 'must be unique' } },
          { status: 400 },
        )),
    )
    const router = makeRouter()
    await router.push('/clinical/content/new')
    const wrapper = mount(ClinicalContentNewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('must be unique')
  })
})
