import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../msw/server'
import EducationListView from '@/views/EducationListView.vue'
import en from '@/i18n/en.json'
import cs from '@/i18n/cs.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en, cs } })

function moduleSummary(title: string) {
  return [{
    moduleSlug: 'ibd-basics',
    topic: 'IBD',
    sortOrder: 1,
    version: 2,
    contentLanguage: 'EN',
    title,
    summary: null,
    lessonCount: 1,
    completedLessonCount: 0,
    completed: false,
    publishedAt: '2026-07-01T00:00:00Z',
  }]
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/education', component: EducationListView },
      { path: '/education/:moduleSlug', component: { template: '<div />' } },
    ],
  })
}

describe('EducationListView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('refetches localized content when the locale changes', async () => {
    let czech = false
    server.use(
      http.get('/api/education/modules', () =>
        HttpResponse.json(czech ? moduleSummary('Základy IBD') : moduleSummary('IBD Basics'))),
    )
    const router = makeRouter()
    await router.push('/education')
    const wrapper = mount(EducationListView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.text()).toContain('IBD Basics')

    czech = true
    i18n.global.locale.value = 'cs'
    await flushPromises()
    expect(wrapper.text()).toContain('Základy IBD')
    i18n.global.locale.value = 'en'
  })
})
