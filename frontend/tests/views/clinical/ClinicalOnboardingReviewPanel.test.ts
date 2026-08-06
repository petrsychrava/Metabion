import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { server } from '../../msw/server'
import ClinicalOnboardingReviewPanel from '@/views/clinical/ClinicalOnboardingReviewPanel.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

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

describe('ClinicalOnboardingReviewPanel', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('drops a stale submission response when the id changes mid-flight', async () => {
    let resolveHeld: (response: HttpResponse<typeof submission>) => void = () => undefined
    server.use(
      http.get('/api/clinical/onboarding/submissions/9', () =>
        new Promise<HttpResponse<typeof submission>>((resolve) => {
          resolveHeld = resolve
        }),
      ),
      http.get('/api/clinical/onboarding/submissions/10', () =>
        HttpResponse.json({ ...submission, id: 10, patientEmail: 'fresh@example.com' }),
      ),
    )
    const wrapper = mount(ClinicalOnboardingReviewPanel, {
      props: { submissionId: 9 },
      global: { plugins: [createPinia(), i18n] },
    })
    await flushPromises()
    expect(wrapper.text()).toContain(en.common.loading)

    await wrapper.setProps({ submissionId: 10 })
    await flushPromises()
    expect(wrapper.text()).toContain('fresh@example.com')

    // The stale response for id 9 lands now and must be dropped.
    resolveHeld(HttpResponse.json(submission))
    await flushPromises()
    expect(wrapper.text()).toContain('fresh@example.com')
    expect(wrapper.text()).not.toContain('patient@example.com')
  })
})
