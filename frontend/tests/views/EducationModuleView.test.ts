import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../msw/server'
import EducationModuleView from '@/views/EducationModuleView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const moduleDetail = {
  moduleSlug: 'ibd-basics',
  topic: 'IBD',
  sortOrder: 1,
  version: 2,
  requestedLanguage: 'EN',
  contentLanguage: 'EN',
  title: 'IBD Basics',
  summary: 'Intro',
  lessonCount: 1,
  completedLessonCount: 0,
  completed: false,
  publishedAt: '2026-07-01T00:00:00Z',
  lessons: [
    {
      lessonSlug: 'what-is-ibd',
      sortOrder: 1,
      requestedLanguage: 'EN',
      contentLanguage: 'EN',
      title: 'What is IBD?',
      summary: null,
      bodyMarkdown: '# What is IBD?',
      bodyHtml: '<h1>What is IBD?</h1>',
      completed: false,
    },
  ],
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/education/:moduleSlug', component: EducationModuleView }],
  })
}

describe('EducationModuleView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders lesson content and toggles completion', async () => {
    let completed = false
    server.use(
      http.get('/api/education/modules/ibd-basics', () =>
        HttpResponse.json({
          ...moduleDetail,
          completedLessonCount: completed ? 1 : 0,
          lessons: [{ ...moduleDetail.lessons[0], completed }],
        }),
      ),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/education/modules/ibd-basics/lessons/what-is-ibd/complete', () => {
        completed = true
        return new HttpResponse(null, { status: 200 })
      }),
      http.delete('/api/education/modules/ibd-basics/lessons/what-is-ibd/complete', () => {
        completed = false
        return new HttpResponse(null, { status: 200 })
      }),
    )
    const router = makeRouter()
    await router.push('/education/ibd-basics')
    const wrapper = mount(EducationModuleView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('IBD Basics')
    expect(wrapper.html()).toContain('<h1>What is IBD?</h1>')

    const toggle = wrapper.find('[data-testid="lesson-toggle-what-is-ibd"]')
    expect(toggle.text()).toContain(en.education.markComplete)
    await toggle.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="lesson-toggle-what-is-ibd"]').text()).toContain(en.education.markIncomplete)
  })
})
