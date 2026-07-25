import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { server } from '../msw/server'
import OnboardingView from '@/views/OnboardingView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const summary7 = {
  id: 7,
  patientProfileId: 4,
  patientEmail: 'p@example.com',
  onboardingContext: null,
  version: 1,
  submittedAt: '2026-07-01T10:00:00Z',
  diagnosisType: 'CROHNS_DISEASE',
  reviewStatus: 'PENDING_REVIEW',
}

const summary8 = {
  ...summary7,
  id: 8,
  version: 2,
  submittedAt: '2026-07-10T10:00:00Z',
  diagnosisType: 'ULCERATIVE_COLITIS',
}

const detail7 = {
  id: 7,
  patientProfileId: 4,
  patientEmail: 'p@example.com',
  onboardingContext: null,
  version: 1,
  createdAt: '2026-07-01T09:00:00Z',
  submittedAt: '2026-07-01T10:00:00Z',
  dateOfBirth: '1990-01-01',
  sex: 'FEMALE',
  countryRegion: 'CZ',
  timezone: 'Europe/Prague',
  diagnosisType: 'CROHNS_DISEASE',
  diagnosisYear: 2018,
  diseaseLocation: null,
  diseaseBehavior: null,
  activityEstimate: 'MILD',
  currentMedications: null,
  steroidUse: 'NONE',
  advancedTherapyExposure: 'NEVER_USED',
  medicationNotes: null,
  labsCollectedAt: '2026-05-20',
  crpMgL: 4.2,
  fecalCalprotectinUgG: null,
  hemoglobinGDl: null,
  albuminGDl: null,
  labNotes: null,
  reviewStatus: 'PENDING_REVIEW',
}

const detail8 = {
  ...detail7,
  id: 8,
  version: 2,
  submittedAt: '2026-07-10T10:00:00Z',
  diagnosisType: 'ULCERATIVE_COLITIS',
  reviewStatus: 'REVIEWED',
}

function mountView() {
  return mount(OnboardingView, { global: { plugins: [createPinia(), i18n] } })
}

describe('OnboardingView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders history summaries from the submissions list', async () => {
    server.use(http.get('/api/onboarding/submissions', () => HttpResponse.json([summary7])))
    const wrapper = mountView()
    await flushPromises()

    const row = wrapper.find('[data-testid="history-item-7"]')
    expect(row.exists()).toBe(true)
    expect(row.text()).toContain('v1')
    expect(row.text()).toContain('2026-07-01')
    expect(row.text()).toContain(en.onboarding.diagnosis.CROHNS_DISEASE)
    expect(row.text()).toContain(en.onboarding.reviewStatus.PENDING_REVIEW)
    expect(wrapper.find('[data-testid="history-detail-7"]').exists()).toBe(false)
  })

  it('expanding a row fetches and renders the full submission detail', async () => {
    let fetchedId: string | null = null
    server.use(
      http.get('/api/onboarding/submissions', () => HttpResponse.json([summary7])),
      http.get('/api/onboarding/submissions/:id', ({ params }) => {
        fetchedId = String(params.id)
        return HttpResponse.json(detail7)
      }),
    )
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[data-testid="history-item-7"]').trigger('click')
    await flushPromises()

    expect(fetchedId).toBe('7')
    const detail = wrapper.find('[data-testid="history-detail-7"]')
    expect(detail.exists()).toBe(true)
    expect(detail.text()).toContain(en.onboarding.diagnosis.CROHNS_DISEASE)
    expect(detail.text()).toContain('4.2')
    expect(detail.text()).toContain(en.onboarding.notProvided)
  })

  it('collapses the open row and shows only one detail at a time', async () => {
    const fetched: string[] = []
    server.use(
      http.get('/api/onboarding/submissions', () => HttpResponse.json([summary7, summary8])),
      http.get('/api/onboarding/submissions/7', () => {
        fetched.push('7')
        return HttpResponse.json(detail7)
      }),
      http.get('/api/onboarding/submissions/8', () => {
        fetched.push('8')
        return HttpResponse.json(detail8)
      }),
    )
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[data-testid="history-item-7"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="history-detail-7"]').exists()).toBe(true)

    // Clicking the open row collapses it.
    await wrapper.find('[data-testid="history-item-7"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="history-detail-7"]').exists()).toBe(false)

    // Expanding another row fetches its own id and shows only its detail.
    await wrapper.find('[data-testid="history-item-8"]').trigger('click')
    await flushPromises()
    expect(fetched).toEqual(['7', '8'])
    expect(wrapper.find('[data-testid="history-detail-7"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="history-detail-8"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="history-detail-8"]').text()).toContain(
      en.onboarding.diagnosis.ULCERATIVE_COLITIS,
    )
  })
})
