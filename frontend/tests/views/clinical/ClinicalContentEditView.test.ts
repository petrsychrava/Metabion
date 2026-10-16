import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import { resetCsrfToken } from '@/api/http'
import ClinicalContentEditView from '@/views/clinical/ClinicalContentEditView.vue'
import en from '@/i18n/en.json'
import cs from '@/i18n/cs.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en, cs } })

function form() {
  return {
    revision: 0,
    slug: 'ibd-basics',
    topic: 'IBD',
    sortOrder: 10,
    englishTitle: 'IBD Basics',
    englishSummary: 'Overview.',
    czechTitle: 'Základy',
    czechSummary: 'Přehled.',
    lessons: [
      {
        slug: 'intro', sortOrder: 10, englishTitle: 'Intro', englishSummary: 'Intro summary.',
        englishBodyMarkdown: '# Hello', czechTitle: 'Úvod', czechSummary: 'Shrnutí úvodu.',
        czechBodyMarkdown: 'Ahoj',
      },
    ],
  }
}

function managementDetail(status = 'DRAFT') {
  return {
    moduleSlug: 'ibd-basics',
    topic: 'IBD',
    sortOrder: 10,
    version: 2,
    status,
    reviewNotes: null,
    reviewBypassed: false,
    englishTitle: 'IBD Basics',
    englishSummary: 'Overview.',
    czechTitle: 'Základy',
    czechSummary: 'Přehled.',
    authorEmail: 'author@example.com',
    reviewedByEmail: null,
    publishedByEmail: null,
    createdAt: '2026-09-11T20:36:42Z',
    submittedAt: null,
    reviewedAt: null,
    publishedAt: null,
    lessons: [],
  }
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical/content/:moduleSlug/:version/edit', component: ClinicalContentEditView, props: true },
      { path: '/clinical/content/:moduleSlug/:version', component: { template: '<div />' } },
    ],
  })
}

describe('ClinicalContentEditView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2', () =>
        HttpResponse.json(managementDetail())),
      http.get('/api/content/education/modules/ibd-basics/versions/2/form', () => HttpResponse.json(form())),
    )
  })

  it('redirects before loading a non-editable version form', async () => {
    let formLoads = 0
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2', () =>
        HttpResponse.json(managementDetail('IN_REVIEW'))),
      http.get('/api/content/education/modules/ibd-basics/versions/2/form', () => {
        formLoads += 1
        return HttpResponse.json(form())
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2')
    expect(formLoads).toBe(0)
  })

  it('loads the form and saves the full draft', async () => {
    let putBody: unknown
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', async ({ request }) => {
        putBody = await request.json()
        return HttpResponse.json({})
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    const body = wrapper.find('textarea[data-testid="markdown-source"]')
    expect(body.exists()).toBe(true)
    await body.setValue('# Hello edited')

    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(putBody).toEqual({
      revision: 0,
      slug: 'ibd-basics',
      topic: 'IBD',
      sortOrder: 10,
      englishTitle: 'IBD Basics',
      englishSummary: 'Overview.',
      czechTitle: 'Základy',
      czechSummary: 'Přehled.',
      lessons: [
        {
          slug: 'intro', sortOrder: 10, englishTitle: 'Intro', englishSummary: 'Intro summary.',
          englishBodyMarkdown: '# Hello edited', czechTitle: 'Úvod', czechSummary: 'Shrnutí úvodu.',
          czechBodyMarkdown: 'Ahoj',
        },
      ],
    })
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2')
  })

  it('freezes lesson edits before waiting for the CSRF token', async () => {
    let resolveCsrf!: (response: Response) => void
    let putBody: unknown
    resetCsrfToken()
    server.use(
      http.get('/api/csrf', () => new Promise((resolve) => {
        resolveCsrf = resolve
      })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', async ({ request }) => {
        putBody = await request.json()
        return HttpResponse.json({})
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(putBody).toBeUndefined()

    await wrapper.find('input[data-testid="lesson-slug-0"]').setValue('intro-edited')
    resolveCsrf(HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' }))
    await flushPromises()

    expect((putBody as { lessons: { slug: string }[] }).lessons[0].slug).toBe('intro')
  })

  it('renders the server-rendered preview tab', async () => {
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/markdown-preview', () => HttpResponse.json({ html: '<h1>Hello</h1>' })),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="markdown-preview-tab"]').trigger('click')
    await flushPromises()
    expect(wrapper.html()).toContain('<h1>Hello</h1>')
  })

  it('surfaces a preview request failure and clears it on a successful retry', async () => {
    const pending: Array<{ respond: (response: Response) => void }> = []
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/markdown-preview', () =>
        new Promise((resolve) => {
          pending.push({ respond: (response) => resolve(response) })
        })),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    // The English body editor is the first MarkdownEditor on the page.
    await wrapper.find('[data-testid="markdown-preview-tab"]').trigger('click')
    await flushPromises()
    expect(pending).toHaveLength(1)

    pending[0].respond(HttpResponse.json({ error: 'request_failed' }, { status: 500 }))
    await flushPromises()

    const pane = wrapper.find('div.prose')
    expect(pane.text()).toContain('The preview could not be rendered. Try again.')
    // The rendered-html slot stays empty; the error line replaces it rather than riding above it.
    expect(pane.find('div').exists()).toBe(false)

    // Retry against a healthy server: the error clears and the rendered html takes over.
    await wrapper.find('[data-testid="markdown-preview-tab"]').trigger('click')
    await flushPromises()
    expect(pending).toHaveLength(2)
    expect(pane.text()).not.toContain('The preview could not be rendered. Try again.')

    pending[1].respond(HttpResponse.json({ html: '<h1>Hello</h1>' }))
    await flushPromises()
    expect(wrapper.html()).toContain('<h1>Hello</h1>')
    expect(wrapper.text()).not.toContain('The preview could not be rendered. Try again.')
  })

  it('keeps the latest source preview when overlapping preview requests resolve out of order', async () => {
    const pending: Array<{ markdown: string; respond: (html: string) => void }> = []
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/markdown-preview', async ({ request }) => {
        const { markdown } = await request.json() as { markdown: string }
        return new Promise((resolve) => {
          pending.push({ markdown, respond: (html) => resolve(HttpResponse.json({ html })) })
        })
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="markdown-preview-tab"]').trigger('click')
    await flushPromises()
    expect(pending.map((p) => p.markdown)).toEqual(['# Hello'])

    // Back on the edit tab, change the source, then preview again so both requests overlap.
    await wrapper.find('[data-testid="markdown-edit-tab"]').trigger('click')
    await wrapper.find('textarea[data-testid="markdown-source"]').setValue('# Hello edited')
    await wrapper.find('[data-testid="markdown-preview-tab"]').trigger('click')
    await flushPromises()
    expect(pending.map((p) => p.markdown)).toEqual(['# Hello', '# Hello edited'])

    // The newer request resolves first; then the stale one resolves last and must be ignored.
    pending[1].respond('<h1>Hello edited</h1>')
    await flushPromises()
    expect(wrapper.html()).toContain('<h1>Hello edited</h1>')

    pending[0].respond('<h1>Hello</h1>')
    await flushPromises()
    expect(wrapper.html()).toContain('<h1>Hello edited</h1>')
    expect(wrapper.html()).not.toContain('<h1>Hello</h1>')
  })

  it('clears the loading state when an empty preview supersedes an in-flight request', async () => {
    const pending: Array<{ respond: (html: string) => void }> = []
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/markdown-preview', () =>
        new Promise((resolve) => {
          pending.push({ respond: (html) => resolve(HttpResponse.json({ html })) })
        })),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="markdown-preview-tab"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Loading…')
    expect(pending).toHaveLength(1)

    // Clear the source while the first request is still in flight, then preview the empty source.
    await wrapper.find('[data-testid="markdown-edit-tab"]').trigger('click')
    await wrapper.find('textarea[data-testid="markdown-source"]').setValue('')
    await wrapper.find('[data-testid="markdown-preview-tab"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('Loading…')

    // The stale response must not resurrect the preview or the spinner.
    pending[0].respond('<h1>Stale</h1>')
    await flushPromises()
    expect(wrapper.text()).not.toContain('Loading…')
    expect(wrapper.html()).not.toContain('<h1>Stale</h1>')
  })

  it('does not prompt about unsaved changes after a successful save', async () => {
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', () => HttpResponse.json({})),
    )
    // Mount through <router-view> so onBeforeRouteLeave registers; a vetoing confirm must not block
    // the post-save navigation because the just-saved state is no longer dirty.
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/clinical',
          component: { template: '<router-view />' },
          children: [
            { path: 'content/:moduleSlug/:version/edit', component: ClinicalContentEditView, props: true },
            { path: 'content/:moduleSlug/:version', component: { template: '<div />' } },
          ],
        },
      ],
    })
    await router.push('/clinical/content/ibd-basics/2/edit')
    await router.isReady()
    const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('textarea[data-testid="markdown-source"]').setValue('# Hello edited')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2')
    expect(confirmSpy).not.toHaveBeenCalled()
    confirmSpy.mockRestore()
  })

  it('keeps mid-flight edits dirty so the leave guard prompts before post-save navigation', async () => {
    const putResolvers: Array<() => void> = []
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', () =>
        new Promise((resolve) => {
          putResolvers.push(() => resolve(HttpResponse.json({})))
        })),
    )
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/clinical',
          component: { template: '<router-view />' },
          children: [
            { path: 'content/:moduleSlug/:version/edit', component: ClinicalContentEditView, props: true },
            { path: 'content/:moduleSlug/:version', component: { template: '<div />' } },
          ],
        },
      ],
    })
    await router.push('/clinical/content/ibd-basics/2/edit')
    await router.isReady()
    const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('textarea[data-testid="markdown-source"]').setValue('# Hello edited')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(putResolvers).toHaveLength(1)

    // Edit another field while the PUT is still in flight; that edit is not in the submitted body.
    await wrapper.find('input[data-testid="english-title"]').setValue('Edited While Saving')

    putResolvers[0]()
    await flushPromises()

    expect(confirmSpy).toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2/edit')
    expect((wrapper.find('input[data-testid="english-title"]').element as HTMLInputElement).value)
      .toBe('Edited While Saving')
    confirmSpy.mockRestore()
  })

  it('does not yank the author back to the detail after they leave mid-save', async () => {
    const putResolvers: Array<() => void> = []
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', () =>
        new Promise((resolve) => {
          putResolvers.push(() => resolve(HttpResponse.json({})))
        })),
    )
    // Mount through <router-view> so the dirty-guarded departure unmounts the editor for real.
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/clinical',
          component: { template: '<router-view />' },
          children: [
            { path: 'content', component: { template: '<div />' } },
            { path: 'content/:moduleSlug/:version/edit', component: ClinicalContentEditView, props: true },
            { path: 'content/:moduleSlug/:version', component: { template: '<div />' } },
          ],
        },
      ],
    })
    await router.push('/clinical/content/ibd-basics/2/edit')
    await router.isReady()
    const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('textarea[data-testid="markdown-source"]').setValue('# Hello edited')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(putResolvers).toHaveLength(1)

    // The author confirms through the dirty guard and leaves while the PUT is still in flight.
    await router.push('/clinical/content')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/clinical/content')

    putResolvers[0]()
    await flushPromises()

    // The completed save must not redirect a departed author back to the old version detail.
    expect(router.currentRoute.value.path).toBe('/clinical/content')
    confirmSpy.mockRestore()
  })

  it('does not redirect a freshly remounted editor after a mid-save leave-and-return', async () => {
    const putResolvers: Array<() => void> = []
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', () =>
        new Promise((resolve) => {
          putResolvers.push(() => resolve(HttpResponse.json({})))
        })),
    )
    // Mount through <router-view> so the dirty-guarded departure unmounts the editor; returning
    // to the same URL remounts a fresh editor instance that the old handler must not hijack.
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/clinical',
          component: { template: '<router-view />' },
          children: [
            { path: 'content', component: { template: '<div />' } },
            { path: 'content/:moduleSlug/:version/edit', component: ClinicalContentEditView, props: true },
            { path: 'content/:moduleSlug/:version', component: { template: '<div />' } },
          ],
        },
      ],
    })
    await router.push('/clinical/content/ibd-basics/2/edit')
    await router.isReady()
    const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('textarea[data-testid="markdown-source"]').setValue('# Hello edited')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(putResolvers).toHaveLength(1)

    // The author confirms through the dirty guard and leaves while the PUT is still in flight.
    await router.push('/clinical/content')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/clinical/content')

    // Returning to the same URL mounts a fresh editor instance at the departed originPath.
    await router.push('/clinical/content/ibd-basics/2/edit')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2/edit')

    putResolvers[0]()
    await flushPromises()
    await flushPromises()

    // The departed editor's save must not push the fresh instance on to the detail route.
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2/edit')
    confirmSpy.mockRestore()
  })

  it('invalidates a pending save when the editor route is revisited', async () => {
    const putResolvers: Array<() => void> = []
    const v3Form = {
      ...form(),
      englishTitle: 'IBD Basics v3',
    }
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/3', () =>
        HttpResponse.json({ ...managementDetail(), version: 3 })),
      http.get('/api/content/education/modules/ibd-basics/versions/3/form', () =>
        HttpResponse.json(v3Form)),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', () =>
        new Promise((resolve) => {
          putResolvers.push(() => resolve(HttpResponse.json({})))
        })),
    )
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/clinical',
          component: { template: '<router-view />' },
          children: [
            { path: 'content/:moduleSlug/:version/edit', component: ClinicalContentEditView, props: true },
            { path: 'content/:moduleSlug/:version', component: { template: '<div />' } },
          ],
        },
      ],
    })
    await router.push('/clinical/content/ibd-basics/2/edit')
    await router.isReady()
    const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('input[data-testid="english-title"]').setValue('Edited v2')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(putResolvers).toHaveLength(1)

    await router.push('/clinical/content/ibd-basics/3/edit')
    await flushPromises()
    await flushPromises()
    expect((wrapper.find('input[data-testid="english-title"]').element as HTMLInputElement).value)
      .toBe('IBD Basics v3')

    await router.push('/clinical/content/ibd-basics/2/edit')
    await flushPromises()
    await flushPromises()
    await wrapper.find('input[data-testid="english-title"]').setValue('Edited after revisit')

    putResolvers[0]()
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2/edit')
    expect((wrapper.find('input[data-testid="english-title"]').element as HTMLInputElement).value)
      .toBe('Edited after revisit')
    confirmSpy.mockRestore()
  })

  it('skips the bail-out navigation when a fieldless 400 arrives after the author left', async () => {
    let formLoads = 0
    const putResolvers: Array<() => void> = []
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2/form', () => {
        formLoads += 1
        return HttpResponse.json(form())
      }),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', () =>
        new Promise((resolve) => {
          putResolvers.push(() => resolve(HttpResponse.json({ error: 'request_failed' }, { status: 400 })))
        })),
    )
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/clinical',
          component: { template: '<router-view />' },
          children: [
            { path: 'content', component: { template: '<div />' } },
            { path: 'content/:moduleSlug/:version/edit', component: ClinicalContentEditView, props: true },
            { path: 'content/:moduleSlug/:version', component: { template: '<div />' } },
          ],
        },
      ],
    })
    await router.push('/clinical/content/ibd-basics/2/edit')
    await router.isReady()
    const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('textarea[data-testid="markdown-source"]').setValue('# Hello edited')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(putResolvers).toHaveLength(1)

    await router.push('/clinical/content')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/clinical/content')

    putResolvers[0]()
    await flushPromises()

    // The bail-out belongs to the live editor only: no redirect, no junk GET, no error clobber.
    expect(formLoads).toBe(1)
    expect(router.currentRoute.value.path).toBe('/clinical/content')
    confirmSpy.mockRestore()
  })

  it('shows an empty preview for a lesson with a null Czech body instead of throwing', async () => {
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2/form', () =>
        HttpResponse.json({
          ...form(),
          lessons: [{ ...form().lessons[0], czechBodyMarkdown: null }],
        })),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/markdown-preview', () =>
        HttpResponse.json({ html: '<h1>Unexpected</h1>' })),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    // The Czech body editor is the last MarkdownEditor on the page.
    const tabs = wrapper.findAll('[data-testid="markdown-preview-tab"]')
    await tabs[tabs.length - 1].trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('Loading…')
    expect(wrapper.html()).not.toContain('Unexpected')
  })

  it('disables saving while a populated lesson row is incomplete', async () => {
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('input[data-testid="lesson-slug-0"]').setValue('')
    expect(wrapper.find('[data-testid="save"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Populated lessons need')
  })

  it('keeps author edits and shows a banner on a validation 400 with field errors', async () => {
    let formLoads = 0
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2/form', () => {
        formLoads += 1
        return HttpResponse.json(form())
      }),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', () =>
        HttpResponse.json(
          { error: 'validation_failed', fields: { 'lessons[0].englishTitle': 'required' } },
          { status: 400 },
        )),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('input[data-testid="english-title"]').setValue('Edited Title')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(formLoads).toBe(1)
    expect((wrapper.find('input[data-testid="english-title"]').element as HTMLInputElement).value).toBe('Edited Title')
    expect(wrapper.text()).toContain('Please check the highlighted fields.')
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2/edit')
  })

  it('keeps author edits and renders field errors when a lesson slug normalizes to blank', async () => {
    let formLoads = 0
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2/form', () => {
        formLoads += 1
        return HttpResponse.json(form())
      }),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', () =>
        HttpResponse.json(
          {
            error: 'validation_failed',
            fields: { 'lessons[0].slugNormalizable': 'lesson slug must contain at least one letter or digit' },
          },
          { status: 400 },
        )),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('input[data-testid="lesson-slug-0"]').setValue('---')
    await wrapper.find('input[data-testid="english-title"]').setValue('Edited Title')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    // A field-carrying validation 400 is not a state race: keep the edits, skip the resync GET.
    expect(formLoads).toBe(1)
    expect((wrapper.find('input[data-testid="lesson-slug-0"]').element as HTMLInputElement).value).toBe('---')
    expect((wrapper.find('input[data-testid="english-title"]').element as HTMLInputElement).value).toBe('Edited Title')
    const fieldErrors = wrapper.find('[data-testid="field-errors"]')
    expect(fieldErrors.exists()).toBe(true)
    expect(fieldErrors.text()).toContain('lesson slug must contain at least one letter or digit')
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2/edit')
  })

  it('bails to the detail page when a fieldless 400 means the state raced', async () => {
    let formLoads = 0
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2/form', () => {
        formLoads += 1
        return HttpResponse.json(form())
      }),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', () =>
        HttpResponse.json({ error: 'request_failed' }, { status: 400 })),
    )
    // Mount through <router-view> so the dirty guard runs on the bail-out navigation; the
    // author confirms, keeping agency over edits they can no longer save.
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/clinical',
          component: { template: '<router-view />' },
          children: [
            { path: 'content/:moduleSlug/:version/edit', component: ClinicalContentEditView, props: true },
            { path: 'content/:moduleSlug/:version', component: { template: '<div />' } },
          ],
        },
      ],
    })
    await router.push('/clinical/content/ibd-basics/2/edit')
    await router.isReady()
    const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('input[data-testid="english-title"]').setValue('Edited Title')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    // A fieldless 400 (state race, e.g. the version left the editable state) bails to the detail
    // page, which shows the current status and valid actions: no resync GET, no stranded form.
    expect(confirmSpy).toHaveBeenCalled()
    expect(formLoads).toBe(1)
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2')
    confirmSpy.mockRestore()
  })

  it('locks the editor and surfaces the state change when the dirty guard vetoes the bail-out', async () => {
    let formLoads = 0
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2/form', () => {
        formLoads += 1
        return HttpResponse.json(form())
      }),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', () =>
        HttpResponse.json({ error: 'request_failed' }, { status: 400 })),
    )
    // Mount through <router-view> so the dirty guard runs on the bail-out navigation; the author
    // vetoes it, so the editor must surface the state change and lock instead of silently dying.
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/clinical',
          component: { template: '<router-view />' },
          children: [
            { path: 'content/:moduleSlug/:version/edit', component: ClinicalContentEditView, props: true },
            { path: 'content/:moduleSlug/:version', component: { template: '<div />' } },
          ],
        },
      ],
    })
    await router.push('/clinical/content/ibd-basics/2/edit')
    await router.isReady()
    const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('input[data-testid="english-title"]').setValue('Edited Title')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(confirmSpy).toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2/edit')
    expect(wrapper.text()).toContain('The version is no longer editable because its status changed.')
    expect(wrapper.find('[data-testid="save"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('input[data-testid="english-title"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('textarea[data-testid="markdown-source"]').attributes('disabled')).toBeDefined()
    expect((wrapper.find('input[data-testid="english-title"]').element as HTMLInputElement).value).toBe('Edited Title')
    expect(formLoads).toBe(1)
    confirmSpy.mockRestore()
  })

  it('omits a freshly added blank lesson row from the save payload', async () => {
    let putBody: unknown
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', async ({ request }) => {
        putBody = await request.json()
        return HttpResponse.json({})
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="add-lesson"]').trigger('click')
    expect(wrapper.find('[data-testid="save"]').attributes('disabled')).toBeUndefined()

    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    const lessons = (putBody as { lessons: { slug: string }[] }).lessons
    expect(lessons).toHaveLength(1)
    expect(lessons[0].slug).toBe('intro')
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2')
  })

  it('disables saving while a lesson has partial Czech content', async () => {
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('textarea[data-testid="czech-summary-0"]').setValue('')
    await wrapper.findAll('textarea[data-testid="markdown-source"]')[1].setValue('')

    expect(wrapper.find('[data-testid="save"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="lesson-row"]').text())
      .toContain('Czech fields are all-or-none: fill the Czech title, summary, and body, or clear all three.')
  })

  it('still allows saving when all Czech fields of a lesson are cleared', async () => {
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('input[data-testid="czech-title-0"]').setValue('')
    await wrapper.find('textarea[data-testid="czech-summary-0"]').setValue('')
    await wrapper.findAll('textarea[data-testid="markdown-source"]')[1].setValue('')

    expect(wrapper.find('[data-testid="save"]').attributes('disabled')).toBeUndefined()
  })

  it('disables saving while only one module-level Czech field is filled', async () => {
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('textarea[data-testid="czech-summary"]').setValue('')

    expect(wrapper.find('[data-testid="save"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="czech-module-incomplete"]').exists()).toBe(true)
    expect(wrapper.text())
      .toContain('Czech module title and summary must both be filled or both be empty.')
  })

  it('renders server field errors as a list and keeps the loaded form', async () => {
    let formLoads = 0
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2/form', () => {
        formLoads += 1
        return HttpResponse.json(form())
      }),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', () =>
        HttpResponse.json(
          { error: 'validation_failed', fields: { 'lessons[0].slug': 'must be unique' } },
          { status: 400 },
        )),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(formLoads).toBe(1)
    const fieldErrors = wrapper.find('[data-testid="field-errors"]')
    expect(fieldErrors.exists()).toBe(true)
    expect(fieldErrors.find('code').text()).toBe('lessons[0].slug')
    expect(fieldErrors.text()).toContain('must be unique')
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2/edit')
  })

  it('offers a reload after an optimistic save conflict', async () => {
    let formLoads = 0
    const putRevisions: number[] = []
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2/form', () => {
        formLoads += 1
        return HttpResponse.json(formLoads === 1 ? form() : { ...form(), revision: 1, englishTitle: 'Current title' })
      }),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', async ({ request }) => {
        const body = await request.json() as { revision: number }
        putRevisions.push(body.revision)
        if (putRevisions.length === 1) return HttpResponse.json({ error: 'conflict' }, { status: 409 })
        return HttpResponse.json({})
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('input[data-testid="english-title"]').setValue('Stale title')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(putRevisions).toEqual([0])
    expect(wrapper.text()).toContain(en.errors.conflict)
    expect(wrapper.find('[data-testid="reload"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="save"]').attributes('disabled')).toBeDefined()

    await wrapper.find('[data-testid="reload"]').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(formLoads).toBe(2)
    expect(wrapper.find('[data-testid="reload"]').exists()).toBe(false)
    expect((wrapper.find('input[data-testid="english-title"]').element as HTMLInputElement).value)
      .toBe('Current title')
    expect(wrapper.find('[data-testid="save"]').attributes('disabled')).toBeUndefined()

    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(putRevisions).toEqual([0, 1])
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2')
  })

  it('keeps module topic and sort order read-only', async () => {
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    const topic = wrapper.find('input[data-testid="topic"]')
    const sortOrder = wrapper.find('input[data-testid="sort-order"]')
    expect(topic.attributes('disabled')).toBeDefined()
    expect(sortOrder.attributes('disabled')).toBeDefined()
    expect(topic.classes()).toContain('disabled:opacity-60')
    expect(sortOrder.classes()).toContain('disabled:opacity-60')
  })

  it('reloads the editor when the route parameters change', async () => {
    let v2Loads = 0
    let v3Loads = 0
    let putUrl: string | null = null
    let putBody: unknown
    const v3Form = {
      ...form(),
      englishTitle: 'IBD Basics v3',
      lessons: [{ ...form().lessons[0], slug: 'intro-v3' }],
    }
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2/form', () => {
        v2Loads += 1
        return HttpResponse.json(form())
      }),
      http.get('/api/content/education/modules/ibd-basics/versions/3', () =>
        HttpResponse.json({ ...managementDetail(), version: 3 })),
      http.get('/api/content/education/modules/ibd-basics/versions/3/form', () => {
        v3Loads += 1
        return HttpResponse.json(v3Form)
      }),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/3', async ({ request }) => {
        putUrl = new URL(request.url).pathname
        putBody = await request.json()
        return HttpResponse.json({})
      }),
    )
    // Mount through <router-view> so the same editor instance is reused across the two edit URLs.
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/clinical',
          component: { template: '<router-view />' },
          children: [
            { path: 'content/:moduleSlug/:version/edit', component: ClinicalContentEditView, props: true },
            { path: 'content/:moduleSlug/:version', component: { template: '<div />' } },
          ],
        },
      ],
    })
    await router.push('/clinical/content/ibd-basics/2/edit')
    await router.isReady()
    const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect((wrapper.find('input[data-testid="english-title"]').element as HTMLInputElement).value)
      .toBe('IBD Basics')

    await router.push('/clinical/content/ibd-basics/3/edit')
    await flushPromises()
    await flushPromises()

    // The v3 form replaces the v2 form under the new URL; the v2 values are gone.
    expect((wrapper.find('input[data-testid="english-title"]').element as HTMLInputElement).value)
      .toBe('IBD Basics v3')
    expect((wrapper.find('input[data-testid="lesson-slug-0"]').element as HTMLInputElement).value)
      .toBe('intro-v3')
    expect(v2Loads).toBe(1)
    expect(v3Loads).toBe(1)

    await wrapper.find('input[data-testid="english-title"]').setValue('IBD Basics v3 edited')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(putUrl).toBe('/api/content/education/modules/ibd-basics/versions/3')
    expect(putBody).toEqual({ ...v3Form, englishTitle: 'IBD Basics v3 edited' })
  })

  it('asks before reloading a dirty editor on route parameter changes', async () => {
    let v3Loads = 0
    const v3Form = {
      ...form(),
      englishTitle: 'IBD Basics v3',
    }
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/3', () =>
        HttpResponse.json({ ...managementDetail(), version: 3 })),
      http.get('/api/content/education/modules/ibd-basics/versions/3/form', () => {
        v3Loads += 1
        return HttpResponse.json(v3Form)
      }),
    )
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/clinical',
          component: { template: '<router-view />' },
          children: [
            { path: 'content/:moduleSlug/:version/edit', component: ClinicalContentEditView, props: true },
            { path: 'content/:moduleSlug/:version', component: { template: '<div />' } },
          ],
        },
      ],
    })
    await router.push('/clinical/content/ibd-basics/2/edit')
    await router.isReady()
    const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('input[data-testid="english-title"]').setValue('Unsaved title')
    await router.push('/clinical/content/ibd-basics/3/edit')
    await flushPromises()

    try {
      expect(confirmSpy).toHaveBeenCalled()
      expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2/edit')
      expect(v3Loads).toBe(0)
      expect((wrapper.find('input[data-testid="english-title"]').element as HTMLInputElement).value)
        .toBe('Unsaved title')
    } finally {
      confirmSpy.mockRestore()
    }
  })

  it('applies the returned revision when a post-save navigation is vetoed', async () => {
    resetCsrfToken()
    const putBodies: Array<{ revision: number }> = []
    let resolveFirstPut!: (response: Response) => void
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', async ({ request }) => {
        putBodies.push(await request.json() as { revision: number })
        if (putBodies.length === 1) {
          return new Promise((resolve) => {
            resolveFirstPut = resolve
          })
        }
        return HttpResponse.json({ revision: 2 })
      }),
    )
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/clinical',
          component: { template: '<router-view />' },
          children: [
            { path: 'content/:moduleSlug/:version/edit', component: ClinicalContentEditView, props: true },
            { path: 'content/:moduleSlug/:version', component: { template: '<div />' } },
          ],
        },
      ],
    })
    await router.push('/clinical/content/ibd-basics/2/edit')
    await router.isReady()
    const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('input[data-testid="english-title"]').setValue('Submitted title')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(putBodies).toHaveLength(1)

    await wrapper.find('input[data-testid="english-title"]').setValue('Kept edits')
    resolveFirstPut(HttpResponse.json({ revision: 1 }))
    await flushPromises()
    await flushPromises()

    expect(confirmSpy).toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2/edit')
    expect((wrapper.find('input[data-testid="english-title"]').element as HTMLInputElement).value)
      .toBe('Kept edits')

    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(putBodies.map((body) => body.revision)).toEqual([0, 1])
    confirmSpy.mockRestore()
  })

  it('ignores failed saves after switching editor routes', async () => {
    const putRejectors: Array<() => void> = []
    const v3Form = {
      ...form(),
      englishTitle: 'IBD Basics v3',
    }
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/3', () =>
        HttpResponse.json({ ...managementDetail(), version: 3 })),
      http.get('/api/content/education/modules/ibd-basics/versions/3/form', () =>
        HttpResponse.json(v3Form)),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', () =>
        new Promise((resolve) => {
          putRejectors.push(() => resolve(HttpResponse.json(
            { error: 'validation_failed', fields: { englishTitle: 'old save failed' } },
            { status: 400 },
          )))
        })),
    )
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/clinical',
          component: { template: '<router-view />' },
          children: [
            { path: 'content/:moduleSlug/:version/edit', component: ClinicalContentEditView, props: true },
            { path: 'content/:moduleSlug/:version', component: { template: '<div />' } },
          ],
        },
      ],
    })
    await router.push('/clinical/content/ibd-basics/2/edit')
    await router.isReady()
    const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('input[data-testid="english-title"]').setValue('Edited v2')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(putRejectors).toHaveLength(1)

    await router.push('/clinical/content/ibd-basics/3/edit')
    await flushPromises()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/3/edit')
    expect((wrapper.find('input[data-testid="english-title"]').element as HTMLInputElement).value)
      .toBe('IBD Basics v3')

    putRejectors[0]()
    await flushPromises()

    expect(wrapper.find('[data-testid="field-errors"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Please check the highlighted fields.')
    expect(wrapper.text()).not.toContain('old save failed')
    confirmSpy.mockRestore()
  })
})
