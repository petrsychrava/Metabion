import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalPatientOnboardingView from '@/views/clinical/ClinicalPatientOnboardingView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const summaries = [
  {
    id: 9,
    patientProfileId: 41,
    patientEmail: 'patient@example.com',
    onboardingContext: 'baseline',
    version: 1,
    submittedAt: '2026-08-01T09:00:00Z',
    diagnosisType: 'CROHNS_DISEASE',
    reviewStatus: 'PENDING_REVIEW',
  },
  {
    id: 10,
    patientProfileId: 42,
    patientEmail: 'other@example.com',
    onboardingContext: 'baseline',
    version: 2,
    submittedAt: '2026-08-02T09:00:00Z',
    diagnosisType: 'ULCERATIVE_COLITIS',
    reviewStatus: 'REVIEWED',
  },
]

const submission = {
  id: 9,
  patientProfileId: 41,
  patientEmail: 'patient@example.com',
  onboardingContext: 'baseline',
  version: 1,
  createdAt: '2026-08-01T09:00:00Z',
  submittedAt: '2026-08-01T09:00:00Z',
  dateOfBirth: null,
  sex: null,
  countryRegion: null,
  timezone: null,
  diagnosisType: 'CROHNS_DISEASE',
  diagnosisYear: 2019,
  diseaseLocation: null,
  diseaseBehavior: null,
  activityEstimate: 'MILD',
  currentMedications: null,
  steroidUse: 'NONE',
  advancedTherapyExposure: 'NEVER_USED',
  medicationNotes: null,
  labsCollectedAt: null,
  crpMgL: null,
  fecalCalprotectinUgG: null,
  hemoglobinGDl: null,
  albuminGDl: null,
  labNotes: null,
  reviewStatus: 'PENDING_REVIEW',
}

describe('ClinicalPatientOnboardingView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('lists only the workspace patient and embeds the review panel on selection', async () => {
    server.use(
      http.get('/api/clinical/onboarding/submissions', () => HttpResponse.json(summaries)),
      http.get('/api/clinical/onboarding/submissions/9', () => HttpResponse.json(submission)),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/clinical/patients/:patientProfileId/onboarding', component: ClinicalPatientOnboardingView }],
    })
    await router.push('/clinical/patients/41/onboarding')
    const wrapper = mount(ClinicalPatientOnboardingView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    const rows = wrapper.findAll('[data-testid="submission-row"]')
    expect(rows).toHaveLength(1)
    expect(rows[0].text()).toContain(en.onboarding.reviewStatus.PENDING_REVIEW)

    await rows[0].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(en.onboarding.diagnosis.CROHNS_DISEASE)
    expect(wrapper.find('[data-testid="submit-review"]').exists()).toBe(true)
  })
})
