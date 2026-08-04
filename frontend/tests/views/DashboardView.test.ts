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

function todayInZone(timezone: string): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date())
  const get = (type: string) => parts.find((p) => p.type === type)?.value
  return `${get('year')}-${get('month')}-${get('day')}`
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

  it('derives today from the patient profile timezone', async () => {
    const today = todayInZone('Pacific/Kiritimati')
    server.use(
      http.get('/api/account/profile', () => HttpResponse.json({
        dateOfBirth: '1990-01-01',
        sex: 'PREFER_NOT_TO_SAY',
        countryRegion: 'CZ',
        timezone: 'Pacific/Kiritimati',
      })),
      // Only the Kiritimati-date endpoints exist; a browser-local date would
      // hit unhandled routes and the statuses would stay "open".
      http.get(`/api/diet-logs/${today}`, () => HttpResponse.json({ id: 1, logDate: today })),
      http.get(`/api/symptom-check-ins/${today}`, () => HttpResponse.json({ id: 2, checkInDate: today })),
    )
    const wrapper = mount(DashboardView, { global: { plugins: [createPinia(), i18n] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="diet-log-status"]').text()).toContain(en.dashboard.dietLogDone)
    expect(wrapper.find('[data-testid="check-in-status"]').text()).toContain(en.dashboard.checkInDone)
  })

  it('shows the red-flag banner for any severity, including routine', async () => {
    server.use(
      http.get(`/api/diet-logs/${todayIso()}`, () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
      http.get(`/api/symptom-check-ins/${todayIso()}`, () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
      http.get('/api/red-flags/current', () => HttpResponse.json({
        highestSeverity: 'ROUTINE_REVIEW',
        flags: [
          {
            eventId: 702,
            ruleKey: 'LAB_CALPROTECTIN_BORDERLINE',
            severity: 'ROUTINE_REVIEW',
            detectedAt: '2026-08-01T09:00:00Z',
            sourceType: 'LAB_RESULT_SET',
            sourceId: 92,
            current: true,
            supersededAt: null,
          },
        ],
      })),
    )
    const wrapper = mount(DashboardView, { global: { plugins: [createPinia(), i18n] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="red-flag-banner"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="red-flag-banner"]').text()).toContain(en.redFlags.severity.ROUTINE_REVIEW)
  })
})
