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
    // The resynced detail and action bar stay visible alongside the error banner.
    expect(wrapper.find('[data-testid="status-badge"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="submit-review"]').exists()).toBe(true)
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

  it('limits the review notes to 2000 characters and surfaces notes field errors', async () => {
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/modules/ibd-basics/versions/2/approve', () =>
        HttpResponse.json(
          { error: 'validation_failed', fields: { notes: 'must not exceed 2000 characters' } },
          { status: 400 },
        )),
    )
    const wrapper = await mountAt({ status: 'IN_REVIEW' }, 'someone-else@example.com', ['PHYSICIAN'])
    await flushPromises()
    await wrapper.find('[data-testid="approve"]').trigger('click')

    const notes = wrapper.find('[data-testid="review-notes"]')
    expect(notes.attributes('maxlength')).toBe('2000')

    await wrapper.find('[data-testid="confirm-approve"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('must not exceed 2000 characters')
  })

  it('ignores concurrent lifecycle clicks while a transition is in flight', async () => {
    let calls = 0
    const pending: Array<() => void> = []
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/modules/ibd-basics/versions/2/approve', () => {
        calls += 1
        return new Promise((resolve) =>
          pending.push(() => resolve(HttpResponse.json(detail({ status: 'APPROVED' })))))
      }),
    )
    const wrapper = await mountAt({ status: 'IN_REVIEW' }, 'someone-else@example.com', ['PHYSICIAN'])
    await flushPromises()
    await wrapper.find('[data-testid="approve"]').trigger('click')

    await wrapper.find('[data-testid="confirm-approve"]').trigger('click')
    await wrapper.find('[data-testid="confirm-approve"]').trigger('click')
    await flushPromises()

    expect(calls).toBe(1)
    expect(wrapper.find('[data-testid="confirm-approve"]').attributes('disabled')).toBeDefined()

    pending.forEach((resolve) => resolve())
    await flushPromises()
    expect(wrapper.text()).toContain('Approved')
  })

  it('ignores a superseded detail response when history returns before the newer load settles', async () => {
    const pending: Array<{ version: number; respond: () => void }> = []
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.get('/api/content/education/modules/ibd-basics/versions/:version', ({ params }) => {
        const requested = Number(params.version)
        return new Promise((resolve) => {
          pending.push({
            version: requested,
            respond: () => resolve(HttpResponse.json(
              detail({ version: requested, englishTitle: requested === 2 ? 'Old' : 'New' }))),
          })
        })
      }),
      http.post('/api/content/education/modules/ibd-basics/versions/2/copy', () =>
        HttpResponse.json(detail({ version: 3, englishTitle: 'New' }))),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2')
    const pinia = createPinia()
    const wrapper = mount(ClinicalContentDetailView, { global: { plugins: [pinia, i18n, router] } })
    useAuthStore(pinia).$patch({ email: 'viewer@example.com', roles: ['PHYSICIAN'], status: 'authenticated' })
    await flushPromises()
    expect(pending.map((p) => p.version)).toEqual([2])

    pending[0].respond()
    await flushPromises()
    expect(wrapper.find('[data-testid="module-english"]').text()).toContain('Old')

    // Copy resolves immediately and navigates to v3, whose load stays pending.
    await wrapper.find('[data-testid="copy"]').trigger('click')
    await flushPromises()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/3')
    expect(pending.map((p) => p.version)).toEqual([2, 3])

    // History returns to v2 before either pending load settles.
    await router.push('/clinical/content/ibd-basics/2')
    await flushPromises()
    expect(pending.map((p) => p.version)).toEqual([2, 3, 2])

    // The latest (v2) load resolves first and renders.
    pending[2].respond()
    await flushPromises()
    expect(wrapper.find('[data-testid="module-english"]').text()).toContain('Old')

    // The superseded v3 response resolving last must not clobber the v2 render or the URL.
    pending[1].respond()
    await flushPromises()
    expect(wrapper.find('[data-testid="module-english"]').text()).toContain('Old')
    expect(wrapper.find('[data-testid="module-english"]').text()).not.toContain('New')
    expect(wrapper.find('[data-testid="status-badge"]').exists()).toBe(true)
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2')
  })

  it('ignores a superseded transition result when navigation lands before the POST settles', async () => {
    const thirdLesson = {
      lessonSlug: 'intro', sortOrder: 10, title: 'Third intro', summary: 'Third summary.',
      bodyMarkdown: '# Third', bodyHtml: '<h1>Third body</h1>',
      czechTitle: null, czechSummary: null, czechBodyMarkdown: null, czechBodyHtml: null,
    }
    const pendingGets: Array<{ version: number; respond: () => void }> = []
    const postResolvers: Array<() => void> = []
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.get('/api/content/education/modules/ibd-basics/versions/:version', ({ params }) => {
        const requested = Number(params.version)
        return new Promise((resolve) => {
          pendingGets.push({
            version: requested,
            respond: () => resolve(HttpResponse.json(requested === 2
              ? detail({ version: 2, englishTitle: 'Second version' })
              : detail({ version: requested, englishTitle: 'Third version', lessons: [thirdLesson] }))),
          })
        })
      }),
      http.post('/api/content/education/modules/ibd-basics/versions/2/submit-review', () =>
        new Promise((resolve) => {
          postResolvers.push(() => resolve(HttpResponse.json(
            detail({ status: 'IN_REVIEW', englishTitle: 'Transitioned second version' }))))
        })),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2')
    const pinia = createPinia()
    const wrapper = mount(ClinicalContentDetailView, { global: { plugins: [pinia, i18n, router] } })
    useAuthStore(pinia).$patch({ email: 'viewer@example.com', roles: ['PHYSICIAN'], status: 'authenticated' })
    await flushPromises()
    pendingGets[0].respond()
    await flushPromises()
    expect(wrapper.find('[data-testid="module-english"]').text()).toContain('Second version')

    // Start the v2 lifecycle POST, then navigate to v3 before it settles.
    await wrapper.find('[data-testid="submit-review"]').trigger('click')
    await flushPromises()
    expect(postResolvers.length).toBe(1)
    await router.push('/clinical/content/ibd-basics/3')
    await flushPromises()
    expect(pendingGets.map((p) => p.version)).toEqual([2, 3])

    // The new route's load resolves first and governs the page.
    pendingGets[1].respond()
    await flushPromises()
    expect(wrapper.find('[data-testid="module-english"]').text()).toContain('Third version')

    // The superseded POST resolving last must not replace the v3 render with the old v2 data.
    postResolvers[0]()
    await flushPromises()
    expect(wrapper.find('[data-testid="module-english"]').text()).toContain('Third version')
    expect(wrapper.html()).toContain('<h1>Third body</h1>')
    expect(wrapper.find('[data-testid="module-english"]').text()).not.toContain('Transitioned second version')
    expect(wrapper.html()).not.toContain('<h1>Hi</h1>')
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/3')
    expect(wrapper.text()).not.toContain('Something went wrong')
  })

  it('ignores a superseded transition failure when navigation lands before the POST settles', async () => {
    const pendingGets: Array<{ version: number; respond: () => void }> = []
    const postResolvers: Array<() => void> = []
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.get('/api/content/education/modules/ibd-basics/versions/:version', ({ params }) => {
        const requested = Number(params.version)
        return new Promise((resolve) => {
          pendingGets.push({
            version: requested,
            respond: () => resolve(HttpResponse.json(
              detail({ version: requested, englishTitle: requested === 2 ? 'Second version' : 'Third version' }))),
          })
        })
      }),
      http.post('/api/content/education/modules/ibd-basics/versions/2/submit-review', () =>
        new Promise((resolve) => {
          postResolvers.push(() => resolve(HttpResponse.json({ error: 'request_failed' }, { status: 400 })))
        })),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2')
    const pinia = createPinia()
    const wrapper = mount(ClinicalContentDetailView, { global: { plugins: [pinia, i18n, router] } })
    useAuthStore(pinia).$patch({ email: 'viewer@example.com', roles: ['PHYSICIAN'], status: 'authenticated' })
    await flushPromises()
    pendingGets[0].respond()
    await flushPromises()
    expect(wrapper.find('[data-testid="module-english"]').text()).toContain('Second version')

    await wrapper.find('[data-testid="submit-review"]').trigger('click')
    await flushPromises()
    expect(postResolvers.length).toBe(1)
    await router.push('/clinical/content/ibd-basics/3')
    await flushPromises()

    pendingGets[1].respond()
    await flushPromises()
    expect(wrapper.find('[data-testid="module-english"]').text()).toContain('Third version')

    // The departed v2 page's failure must not surface a banner or trigger a resync load.
    postResolvers[0]()
    await flushPromises()
    expect(wrapper.find('[data-testid="module-english"]').text()).toContain('Third version')
    expect(wrapper.text()).not.toContain('Something went wrong')
    expect(pendingGets.map((p) => p.version)).toEqual([2, 3])
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/3')
  })

  it('ignores a double-clicked copy while the first copy is in flight', async () => {
    let copyCalls = 0
    const pending: Array<() => void> = []
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.get('/api/content/education/modules/ibd-basics/versions/:version', ({ params }) =>
        HttpResponse.json(detail({ version: Number(params.version), englishTitle: 'New' }))),
      http.post('/api/content/education/modules/ibd-basics/versions/2/copy', () => {
        copyCalls += 1
        return new Promise((resolve) => {
          pending.push(() => resolve(HttpResponse.json(detail({ version: 3, englishTitle: 'New' }))))
        })
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2')
    const pinia = createPinia()
    const wrapper = mount(ClinicalContentDetailView, { global: { plugins: [pinia, i18n, router] } })
    useAuthStore(pinia).$patch({ email: 'viewer@example.com', roles: ['PHYSICIAN'], status: 'authenticated' })
    await flushPromises()

    await wrapper.find('[data-testid="copy"]').trigger('click')
    await wrapper.find('[data-testid="copy"]').trigger('click')
    await flushPromises()
    expect(copyCalls).toBe(1)
    expect(wrapper.find('[data-testid="copy"]').attributes('disabled')).toBeDefined()

    pending.forEach((resolve) => resolve())
    await flushPromises()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/3')
    expect(wrapper.find('[data-testid="module-english"]').text()).toContain('New')
  })

  it('blocks copy while a lifecycle transition is in flight', async () => {
    let copyCalls = 0
    let reviewCalls = 0
    const pending: Array<() => void> = []
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/modules/ibd-basics/versions/2/approve', () => {
        reviewCalls += 1
        return new Promise((resolve) => {
          pending.push(() => resolve(HttpResponse.json(detail({ status: 'APPROVED' }))))
        })
      }),
      http.post('/api/content/education/modules/ibd-basics/versions/2/copy', () => {
        copyCalls += 1
        return HttpResponse.json(detail({ version: 3 }))
      }),
    )
    const wrapper = await mountAt({ status: 'IN_REVIEW' }, 'someone-else@example.com', ['PHYSICIAN'])
    await flushPromises()

    await wrapper.find('[data-testid="approve"]').trigger('click')
    await wrapper.find('[data-testid="confirm-approve"]').trigger('click')
    await flushPromises()
    expect(reviewCalls).toBe(1)
    expect(wrapper.find('[data-testid="copy"]').attributes('disabled')).toBeDefined()

    await wrapper.find('[data-testid="copy"]').trigger('click')
    await flushPromises()
    expect(copyCalls).toBe(0)

    pending.forEach((resolve) => resolve())
    await flushPromises()
    expect(wrapper.text()).toContain('Approved')
  })
})
