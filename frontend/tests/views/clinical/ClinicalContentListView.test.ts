import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalContentListView from '@/views/clinical/ClinicalContentListView.vue'
import en from '@/i18n/en.json'
import cs from '@/i18n/cs.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en, cs } })

function versions() {
  return [
    {
      moduleSlug: 'ibd-basics', topic: 'IBD', version: 2, status: 'PUBLISHED', title: 'IBD Basics',
      authorEmail: 'author@example.com', reviewedByEmail: null, publishedByEmail: 'author@example.com',
      createdAt: '2026-08-01T10:00:00Z', submittedAt: null, reviewedAt: null, publishedAt: '2026-08-02T10:00:00Z',
    },
    {
      moduleSlug: 'ibd-basics', topic: 'IBD', version: 3, status: 'DRAFT', title: 'IBD Basics v3',
      authorEmail: 'me@example.com', reviewedByEmail: null, publishedByEmail: null,
      createdAt: '2026-09-01T10:00:00Z', submittedAt: null, reviewedAt: null, publishedAt: null,
    },
    {
      moduleSlug: 'keto-guide', topic: 'Keto', version: 1, status: 'IN_REVIEW', title: 'Keto Guide',
      authorEmail: 'other@example.com', reviewedByEmail: null, publishedByEmail: null,
      createdAt: '2026-09-05T10:00:00Z', submittedAt: '2026-09-06T10:00:00Z', reviewedAt: null, publishedAt: null,
    },
  ]
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical/content', component: ClinicalContentListView },
      { path: '/clinical/content/new', component: { template: '<div />' } },
      { path: '/clinical/content/:moduleSlug/:version', component: { template: '<div />' } },
    ],
  })
}

async function mountAt(path: string) {
  const router = makeRouter()
  await router.push(path)
  const wrapper = mount(ClinicalContentListView, {
    global: { plugins: [createPinia(), i18n, router] },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('ClinicalContentListView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    server.use(
      http.get('/api/content/education/modules', () => HttpResponse.json(versions())),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
    )
  })

  it('renders all versions with localized status badges', async () => {
    const { wrapper } = await mountAt('/clinical/content')
    expect(wrapper.findAll('[data-testid="content-row"]')).toHaveLength(3)
    expect(wrapper.text()).toContain('IBD Basics v3')
    expect(wrapper.text()).toContain('Draft')
    expect(wrapper.text()).toContain('In review')
    expect(wrapper.text()).toContain('Published')
  })

  it('filters rows by status', async () => {
    const { wrapper } = await mountAt('/clinical/content')
    await wrapper.find('[data-testid="status-filter"]').setValue('DRAFT')
    expect(wrapper.findAll('[data-testid="content-row"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('IBD Basics v3')
  })

  it('navigates to the version detail on row click', async () => {
    const { wrapper, router } = await mountAt('/clinical/content')
    await wrapper.find('[data-testid="content-row"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2')
  })

  it('copies a version and navigates to the new draft', async () => {
    server.use(
      http.post('/api/content/education/modules/ibd-basics/versions/2/copy', () =>
        HttpResponse.json({ moduleSlug: 'ibd-basics', version: 3 })),
    )
    const { wrapper, router } = await mountAt('/clinical/content')
    await wrapper.find('[data-testid="copy-version"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/3')
  })

  it('serializes concurrent copy requests while one is in flight', async () => {
    let postCalls = 0
    let resolveHeld: (response: HttpResponse<{ moduleSlug: string; version: number }>) => void = () => undefined
    server.use(
      http.post('/api/content/education/modules/ibd-basics/versions/2/copy', () => {
        postCalls += 1
        return new Promise<HttpResponse<{ moduleSlug: string; version: number }>>((resolve) => {
          resolveHeld = resolve
        })
      }),
    )
    const { wrapper, router } = await mountAt('/clinical/content')
    const button = wrapper.find('[data-testid="copy-version"]')

    await button.trigger('click')
    await flushPromises()
    expect(postCalls).toBe(1)
    expect(button.attributes('disabled')).toBeDefined()

    // A second click while the copy is in flight must not issue another request.
    await button.trigger('click')
    await flushPromises()
    expect(postCalls).toBe(1)

    resolveHeld(HttpResponse.json({ moduleSlug: 'ibd-basics', version: 3 }))
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/3')
    expect(wrapper.find('[data-testid="copy-version"]').attributes('disabled')).toBeUndefined()
  })
})
