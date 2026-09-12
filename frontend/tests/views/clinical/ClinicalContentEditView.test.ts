import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalContentEditView from '@/views/clinical/ClinicalContentEditView.vue'
import en from '@/i18n/en.json'
import cs from '@/i18n/cs.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en, cs } })

function form() {
  return {
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
      http.get('/api/content/education/modules/ibd-basics/versions/2/form', () => HttpResponse.json(form())),
    )
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
})
