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
      { path: '/clinical/content', component: { template: '<div />' } },
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

  it('ignores a create result after navigating away from the form', async () => {
    const postResolvers: Array<() => void> = []
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/modules', () =>
        new Promise((resolve) => {
          postResolvers.push(() => resolve(HttpResponse.json({ moduleSlug: 'ibd-basics', version: 1 })))
        })),
    )
    const router = makeRouter()
    await router.push('/clinical/content/new')
    const wrapper = mount(ClinicalContentNewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="slug"]').setValue('ibd-basics')
    await wrapper.find('[data-testid="create"]').trigger('submit')
    await flushPromises()
    expect(postResolvers).toHaveLength(1)

    // Leave for the list route before the POST settles, then let it resolve.
    await router.push('/clinical/content')
    await flushPromises()

    postResolvers[0]()
    await flushPromises()
    await flushPromises()

    // A completed background create must not override the user's later navigation.
    expect(router.currentRoute.value.path).toBe('/clinical/content')
  })

  it('disables all inputs and the submit button while the create request is pending', async () => {
    const postResolvers: Array<() => void> = []
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/modules', () =>
        new Promise((resolve) => {
          postResolvers.push(() => resolve(HttpResponse.json({ moduleSlug: 'ibd-basics', version: 1 })))
        })),
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
    expect(postResolvers).toHaveLength(1)

    // The request body is captured at submit time, so edits during the POST would be
    // silently discarded by the redirect; every field locks until the request settles.
    for (const testid of ['slug', 'topic', 'sort-order', 'english-title', 'english-summary', 'czech-title', 'czech-summary']) {
      expect(wrapper.find(`[data-testid="${testid}"]`).attributes('disabled')).toBeDefined()
    }
    expect(wrapper.find('[data-testid="create"]').attributes('disabled')).toBeDefined()

    postResolvers[0]()
    await flushPromises()
  })

  it('ignores a create result that resolves after leaving and returning to the form', async () => {
    const postResolvers: Array<() => void> = []
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/modules', () =>
        new Promise((resolve) => {
          postResolvers.push(() => resolve(HttpResponse.json({ moduleSlug: 'ibd-basics', version: 1 })))
        })),
    )
    const router = makeRouter()
    await router.push('/clinical/content/new')
    const wrapper = mount(ClinicalContentNewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="slug"]').setValue('ibd-basics')
    await wrapper.find('[data-testid="create"]').trigger('submit')
    await flushPromises()
    expect(postResolvers).toHaveLength(1)

    // Leave for the list, unmount the old form, and return: the same URL mounts a fresh
    // instance whose path matches the departed handler's originPath.
    await router.push('/clinical/content')
    await flushPromises()
    wrapper.unmount()
    await router.push('/clinical/content/new')
    await flushPromises()
    mount(ClinicalContentNewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    // The departed handler must not redirect the fresh instance into the draft.
    postResolvers[0]()
    await flushPromises()
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/clinical/content/new')
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

  it('maps a slugNormalizable rejection onto the slug field', async () => {
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/modules', () =>
        HttpResponse.json(
          { error: 'validation_failed', fields: { slugNormalizable: 'module slug must contain at least one letter or digit' } },
          { status: 400 },
        )),
    )
    const router = makeRouter()
    await router.push('/clinical/content/new')
    const wrapper = mount(ClinicalContentNewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const slugLabel = wrapper.find('[data-testid="slug"]').element.closest('label')
    expect(slugLabel?.textContent).toContain('module slug must contain at least one letter or digit')
    expect(router.currentRoute.value.path).toBe('/clinical/content/new')
  })

  it('blocks creation when only one Czech module field is filled', async () => {
    let posts = 0
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/modules', () => {
        posts += 1
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
    await wrapper.find('[data-testid="czech-title"]').setValue('Základy IBD')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[data-testid="czech-module-incomplete"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Czech module title and summary must both be filled or both be empty.')
    expect(posts).toBe(0)
    expect(router.currentRoute.value.path).toBe('/clinical/content/new')
  })
})
