import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import ClinicalShell from '@/components/ClinicalShell.vue'
import en from '@/i18n/en.json'

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  configurable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }),
})

describe('ClinicalShell', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    document.documentElement.classList.remove('dark')
  })

  it('renders the clinical nav without a red-flag banner', async () => {
    const stub = { template: '<div />' }
    const router = createRouter({
      history: createMemoryHistory(),
      routes: ['/clinical', '/clinical/onboarding', '/clinical/education'].map((path) => ({
        path,
        component: stub,
      })),
    })
    await router.push('/clinical')
    const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })
    const wrapper = mount(ClinicalShell, { global: { plugins: [createPinia(), i18n, router] } })

    expect(wrapper.text()).toContain(en.clinical.navOverview)
    expect(wrapper.text()).toContain(en.clinical.navReview)
    expect(wrapper.text()).toContain(en.nav.education)
    expect(wrapper.html()).toContain('href="/clinical/onboarding"')
    expect(wrapper.find('[data-testid="red-flag-banner"]').exists()).toBe(false)
  })
})
