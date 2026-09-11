import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
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

  it('disables saving while a populated lesson row is incomplete', async () => {
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('input[data-testid="lesson-slug-0"]').setValue('')
    expect(wrapper.find('[data-testid="save"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Populated lessons need')
  })
})
