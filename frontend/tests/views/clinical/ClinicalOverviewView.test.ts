import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalOverviewView from '@/views/clinical/ClinicalOverviewView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

function isoDaysAgo(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() - days)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const rows = [
  {
    patientProfileId: 1,
    patientEmail: 'ok@example.com',
    currentRedFlagCount: 0,
    highestRedFlagSeverity: null,
    latestFlareState: 'NO_FLARE',
    latestSymptomScore: 2,
    latestSymptomCheckInDate: isoDaysAgo(0),
    latestKetoneValue: 1.2,
    latestKetoneUnit: 'MMOL_L',
    latestKetoneMeasuredAt: '2026-08-04T06:30:00Z',
    latestAdherenceLevel: 'FULL',
    lastActivityDate: isoDaysAgo(0),
    pendingOnboardingCount: 0,
  },
  {
    patientProfileId: 2,
    patientEmail: 'flagged@example.com',
    currentRedFlagCount: 2,
    highestRedFlagSeverity: 'EMERGENCY',
    latestFlareState: 'ACTIVE_FLARE',
    latestSymptomScore: 9,
    latestSymptomCheckInDate: isoDaysAgo(1),
    latestKetoneValue: null,
    latestKetoneUnit: null,
    latestKetoneMeasuredAt: null,
    latestAdherenceLevel: 'PARTIAL',
    lastActivityDate: isoDaysAgo(1),
    pendingOnboardingCount: 1,
  },
  {
    patientProfileId: 3,
    patientEmail: 'stale@example.com',
    currentRedFlagCount: 0,
    highestRedFlagSeverity: null,
    latestFlareState: 'NO_FLARE',
    latestSymptomScore: 1,
    latestSymptomCheckInDate: isoDaysAgo(9),
    latestKetoneValue: 0.8,
    latestKetoneUnit: 'MMOL_L',
    latestKetoneMeasuredAt: '2026-07-26T06:30:00Z',
    latestAdherenceLevel: 'MOSTLY',
    lastActivityDate: isoDaysAgo(9),
    pendingOnboardingCount: 0,
  },
]

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical', component: ClinicalOverviewView },
      { path: '/clinical/onboarding', component: { template: '<div />' } },
      { path: '/clinical/patients/:patientProfileId/check-ins', component: { template: '<div />' } },
    ],
  })
}

describe('ClinicalOverviewView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('sorts needs-attention-first and renders badges', async () => {
    server.use(http.get('/api/clinical/overview', () => HttpResponse.json(rows)))
    const router = makeRouter()
    await router.push('/clinical')
    const wrapper = mount(ClinicalOverviewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    const emails = wrapper.findAll('[data-testid="overview-row"]').map((r) => r.attributes('data-email'))
    expect(emails).toEqual(['flagged@example.com', 'stale@example.com', 'ok@example.com'])

    const flagged = wrapper.find('[data-testid="overview-row"][data-email="flagged@example.com"]')
    expect(flagged.text()).toContain(en.redFlags.severity.EMERGENCY)
    expect(flagged.text()).toContain(en.clinical.pendingReviews.replace('{count}', '1'))
    // The flare cell carries the symptom score as an attention signal, not just the label.
    expect(flagged.text()).toContain(`${en.checkIn.FlareState.ACTIVE_FLARE} · 9`)

    // The ketone cell carries the measurement date so an old value can't pass for a fresh one.
    const ok = wrapper.find('[data-testid="overview-row"][data-email="ok@example.com"]')
    const ketoneDate = new Date('2026-08-04T06:30:00Z').toLocaleDateString('en')
    expect(ok.text()).toContain(`1.2 ${en.enums.MeasurementUnit.MMOL_L} · ${ketoneDate}`)

    const stale = wrapper.find('[data-testid="overview-row"][data-email="stale@example.com"]')
    expect(stale.text()).toContain(en.clinical.stale)
  })

  it('navigates to the patient workspace', async () => {
    server.use(http.get('/api/clinical/overview', () => HttpResponse.json(rows)))
    const router = makeRouter()
    await router.push('/clinical')
    const wrapper = mount(ClinicalOverviewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="overview-row"][data-email="flagged@example.com"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/clinical/patients/2/check-ins')
  })

  it('shows the empty state with a link to the review queue', async () => {
    server.use(http.get('/api/clinical/overview', () => HttpResponse.json([])))
    const router = makeRouter()
    await router.push('/clinical')
    const wrapper = mount(ClinicalOverviewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain(en.clinical.overviewEmpty)
    expect(wrapper.html()).toContain('href="/clinical/onboarding"')
  })
})
