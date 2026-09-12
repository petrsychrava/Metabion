import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalContentDetailView from '@/views/clinical/ClinicalContentDetailView.vue'
import { useAuthStore } from '@/stores/auth'
import en from '@/i18n/en.json'
import cs from '@/i18n/cs.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en, cs } })

function detail(overrides: Record<string, unknown> = {}) {
  return {
    moduleSlug: 'ibd-basics',
    topic: 'IBD',
    sortOrder: 10,
    version: 2,
    status: 'DRAFT',
    reviewNotes: null,
    reviewBypassed: false,
    englishTitle: 'IBD Basics',
    englishSummary: 'English overview.',
    czechTitle: 'Základy IBD',
    czechSummary: 'Český přehled.',
    authorEmail: 'author@example.com',
    reviewedByEmail: null,
    publishedByEmail: null,
    createdAt: '2026-08-01T10:00:00Z',
    submittedAt: null,
    reviewedAt: null,
    publishedAt: null,
    lessons: [
      {
        lessonSlug: 'intro', sortOrder: 10, title: 'Intro', summary: 'Intro summary.',
        bodyMarkdown: '# Hi', bodyHtml: '<h1>Hi</h1>',
        czechTitle: 'Úvod', czechSummary: 'Shrnutí úvodu.',
        czechBodyMarkdown: 'Ahoj', czechBodyHtml: '<p>Ahoj</p>',
      },
    ],
    ...overrides,
  }
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical/content', component: { template: '<div />' } },
      { path: '/clinical/content/:moduleSlug/:version', component: ClinicalContentDetailView, props: true },
      { path: '/clinical/content/:moduleSlug/:version/edit', component: { template: '<div />' } },
    ],
  })
}

function mountAt(overrides: Record<string, unknown> | undefined, email: string, roles: string[]) {
  if (overrides !== undefined) {
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2', () =>
        HttpResponse.json(detail(overrides))),
    )
  }
  const router = makeRouter()
  return router.push('/clinical/content/ibd-basics/2').then(() => {
    const pinia = createPinia()
    const wrapper = mount(ClinicalContentDetailView, { global: { plugins: [pinia, i18n, router] } })
    useAuthStore(pinia).$patch({ email, roles, status: 'authenticated' })
    return wrapper
  })
}

async function mountDetail(overrides: Record<string, unknown> = {}) {
  const wrapper = await mountAt(overrides, 'viewer@example.com', ['PHYSICIAN'])
  await flushPromises()
  return wrapper
}

describe('ClinicalContentDetailView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('shows draft actions, lesson previews, and both localizations', async () => {
    const wrapper = await mountDetail()
    expect(wrapper.find('[data-testid="submit-review"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="approve"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="publish"]').exists()).toBe(false)
    expect(wrapper.html()).toContain('<h1>Hi</h1>')
    expect(wrapper.find('[data-testid="module-english"]').text()).toContain('IBD Basics')
    expect(wrapper.find('[data-testid="module-czech"]').text()).toContain('Základy IBD')
    // Accordion opens on the first lesson; the Czech preview rides along.
    expect(wrapper.html()).toContain('<p>Ahoj</p>')
    expect(wrapper.text()).toContain('Úvod')
    // Authored summaries render for both locales.
    expect(wrapper.text()).toContain('Intro summary.')
    expect(wrapper.text()).toContain('Shrnutí úvodu.')
  })

  it('hides approval from the author but shows it to another reviewer', async () => {
    const own = await mountAt({ status: 'IN_REVIEW' }, 'author@example.com', ['PHYSICIAN'])
    await flushPromises()
    expect(own.find('[data-testid="approve"]').exists()).toBe(false)

    const other = await mountAt({ status: 'IN_REVIEW' }, 'someone-else@example.com', ['PHYSICIAN'])
    await flushPromises()
    expect(other.find('[data-testid="approve"]').exists()).toBe(true)
    expect(other.find('[data-testid="reject"]').exists()).toBe(true)
  })

  it('lets an admin approve their own content', async () => {
    const wrapper = await mountAt({ status: 'IN_REVIEW' }, 'author@example.com', ['ADMIN'])
    await flushPromises()
    expect(wrapper.find('[data-testid="approve"]').exists()).toBe(true)
  })

  it('submitting for review updates the status in place', async () => {
    let calls = 0
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.get('/api/content/education/modules/ibd-basics/versions/2', () =>
        HttpResponse.json(detail(calls === 0 ? { status: 'DRAFT' } : { status: 'IN_REVIEW' }))),
      http.post('/api/content/education/modules/ibd-basics/versions/2/submit-review', () => {
        calls += 1
        return HttpResponse.json(detail({ status: 'IN_REVIEW' }))
      }),
    )
    const wrapper = await mountAt(undefined, 'viewer@example.com', ['PHYSICIAN'])
    await flushPromises()
    await wrapper.find('[data-testid="submit-review"]').trigger('click')
    await flushPromises()
    expect(calls).toBe(1)
    expect(wrapper.text()).toContain('In review')
  })

  it('disables publish when the version is not publishable', async () => {
    const wrapper = await mountDetail({ status: 'APPROVED', lessons: [] })
    const publish = wrapper.find('[data-testid="publish"]')
    expect(publish.attributes('disabled')).toBeDefined()
    expect(publish.attributes('title')).toContain('Publishing requires')
  })

  it('shows an error banner and resyncs when a transition is rejected', async () => {
    let getCalls = 0
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.get('/api/content/education/modules/ibd-basics/versions/2', () => {
        getCalls += 1
        return HttpResponse.json(detail())
      }),
      http.post('/api/content/education/modules/ibd-basics/versions/2/submit-review', () =>
        HttpResponse.json({ error: 'request_failed' }, { status: 400 })),
    )
    const wrapper = await mountAt(undefined, 'viewer@example.com', ['PHYSICIAN'])
    await flushPromises()
    await wrapper.find('[data-testid="submit-review"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Something went wrong')
    expect(getCalls).toBe(2)
  })

  it('reloads the detail after copy navigates to a new version on the same route', async () => {
    const newLesson = {
      lessonSlug: 'intro', sortOrder: 10, title: 'New intro', summary: null,
      bodyMarkdown: '# New', bodyHtml: '<h1>New</h1>',
      czechTitle: null, czechSummary: null, czechBodyMarkdown: null, czechBodyHtml: null,
    }
    const getVersions: number[] = []
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.get('/api/content/education/modules/ibd-basics/versions/:version', ({ params }) => {
        const requested = Number(params.version)
        getVersions.push(requested)
        return HttpResponse.json(requested === 2
          ? detail({ version: 2, englishTitle: 'Old' })
          : detail({ version: requested, englishTitle: 'New', lessons: [newLesson] }))
      }),
      http.post('/api/content/education/modules/ibd-basics/versions/2/copy', () =>
        HttpResponse.json(detail({ version: 3, englishTitle: 'New', lessons: [newLesson] }))),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2')
    const pinia = createPinia()
    const wrapper = mount(ClinicalContentDetailView, { global: { plugins: [pinia, i18n, router] } })
    useAuthStore(pinia).$patch({ email: 'viewer@example.com', roles: ['PHYSICIAN'], status: 'authenticated' })
    await flushPromises()
    expect(wrapper.text()).toContain('Old')

    await wrapper.find('[data-testid="copy"]').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(getVersions).toContain(3)
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/3')
    expect(wrapper.find('[data-testid="module-english"]').text()).toContain('New')
    expect(wrapper.html()).toContain('<h1>New</h1>')
  })

  it('clears the stale detail and shows the error banner when the reload after copy fails', async () => {
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.get('/api/content/education/modules/ibd-basics/versions/:version', ({ params }) =>
        Number(params.version) === 2
          ? HttpResponse.json(detail({ version: 2, englishTitle: 'Old' }))
          : HttpResponse.json({ error: 'request_failed' }, { status: 500 })),
      http.post('/api/content/education/modules/ibd-basics/versions/2/copy', () =>
        HttpResponse.json(detail({ version: 3, englishTitle: 'New' }))),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2')
    const pinia = createPinia()
    const wrapper = mount(ClinicalContentDetailView, { global: { plugins: [pinia, i18n, router] } })
    useAuthStore(pinia).$patch({ email: 'viewer@example.com', roles: ['PHYSICIAN'], status: 'authenticated' })
    await flushPromises()
    expect(wrapper.text()).toContain('Old')

    await wrapper.find('[data-testid="copy"]').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/3')
    expect(wrapper.text()).not.toContain('Old')
    expect(wrapper.find('[data-testid="status-badge"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="module-english"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Something went wrong')
  })
})
