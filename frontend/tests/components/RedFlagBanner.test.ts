import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, getActivePinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import RedFlagBanner from '@/components/RedFlagBanner.vue'
import { useRedFlagsStore } from '@/stores/redFlags'
import type { PatientRedFlagSnapshot, RedFlagSeverity } from '@/types/api'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/red-flags', component: { template: '<div />' } }],
  })
}

const urgentSnapshot: PatientRedFlagSnapshot = {
  highestSeverity: 'URGENT_REVIEW',
  flags: [
    {
      eventId: 701,
      ruleKey: 'LAB_CRP_HIGH',
      severity: 'URGENT_REVIEW',
      detectedAt: '2026-08-01T10:15:30Z',
      sourceType: 'LAB_RESULT_SET',
      sourceId: 91,
      current: true,
      supersededAt: null,
    },
  ],
}

function mountBanner(severities: RedFlagSeverity[]) {
  // Reuse the active pinia from beforeEach so store mutations in the test are
  // visible to the component (a fresh createPinia() here would be a separate
  // instance that the component injects instead).
  return mount(RedFlagBanner, {
    props: { severities },
    global: { plugins: [getActivePinia()!, i18n, makeRouter()] },
  })
}

describe('RedFlagBanner', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders nothing without a snapshot', () => {
    const wrapper = mountBanner(['ROUTINE_REVIEW', 'URGENT_REVIEW', 'EMERGENCY'])
    expect(wrapper.find('[data-testid="red-flag-banner"]').exists()).toBe(false)
  })

  it('renders nothing when the last load failed', () => {
    const store = useRedFlagsStore()
    store.snapshot = urgentSnapshot
    store.loadFailed = true
    const wrapper = mountBanner(['URGENT_REVIEW'])
    expect(wrapper.find('[data-testid="red-flag-banner"]').exists()).toBe(false)
  })

  it('renders nothing when the highest severity is excluded by the prop', () => {
    const store = useRedFlagsStore()
    store.snapshot = urgentSnapshot
    const wrapper = mountBanner(['EMERGENCY'])
    expect(wrapper.find('[data-testid="red-flag-banner"]').exists()).toBe(false)
  })

  it('shows the severity label, flag count, and detail link when visible', () => {
    const store = useRedFlagsStore()
    store.snapshot = urgentSnapshot
    const wrapper = mountBanner(['URGENT_REVIEW', 'EMERGENCY'])
    const banner = wrapper.find('[data-testid="red-flag-banner"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain(en.redFlags.severity.URGENT_REVIEW)
    expect(banner.text()).toContain('1')
    expect(banner.html()).toContain('href="/red-flags"')
    expect(banner.classes().join(' ')).toContain('amber')
  })
})
