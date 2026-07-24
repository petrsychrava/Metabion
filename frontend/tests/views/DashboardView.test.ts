import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { server } from '../msw/server'
import DashboardView from '@/views/DashboardView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

function todayIso(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

describe('DashboardView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('shows both items as open when nothing exists today', async () => {
    server.use(
      http.get(`/api/diet-logs/${todayIso()}`, () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
      http.get(`/api/symptom-check-ins/${todayIso()}`, () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
    )
    const wrapper = mount(DashboardView, { global: { plugins: [createPinia(), i18n] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="diet-log-status"]').text()).toContain(en.dashboard.dietLogOpen)
    expect(wrapper.find('[data-testid="check-in-status"]').text()).toContain(en.dashboard.checkInOpen)
  })

  it('shows completed states when today is filled', async () => {
    server.use(
      http.get(`/api/diet-logs/${todayIso()}`, () => HttpResponse.json({ id: 1, logDate: todayIso() })),
      http.get(`/api/symptom-check-ins/${todayIso()}`, () => HttpResponse.json({ id: 2, checkInDate: todayIso() })),
    )
    const wrapper = mount(DashboardView, { global: { plugins: [createPinia(), i18n] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="diet-log-status"]').text()).toContain(en.dashboard.dietLogDone)
    expect(wrapper.find('[data-testid="check-in-status"]').text()).toContain(en.dashboard.checkInDone)
  })
})
