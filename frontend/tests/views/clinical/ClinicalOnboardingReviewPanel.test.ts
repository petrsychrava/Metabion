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

  it('resets the decision form when the submission changes', async () => {
    server.use(
      http.get('/api/clinical/onboarding/submissions/9', () => HttpResponse.json(submission)),
      http.get('/api/clinical/onboarding/submissions/10', () =>
        HttpResponse.json({ ...submission, id: 10, patientEmail: 'fresh@example.com' }),
      ),
    )
    const wrapper = mount(ClinicalOnboardingReviewPanel, {
      props: { submissionId: 9 },
      global: { plugins: [createPinia(), i18n] },
    })
    await flushPromises()

    await wrapper.find('[data-testid="review-decision"]').setValue('NEEDS_FOLLOW_UP')
    await wrapper.find('[data-testid="review-notes"]').setValue('note for the previous submission')

    await wrapper.setProps({ submissionId: 10 })
    await flushPromises()

    expect(wrapper.text()).toContain('fresh@example.com')
    expect((wrapper.find('[data-testid="review-decision"]').element as HTMLSelectElement).value).toBe('REVIEWED')
    expect((wrapper.find('[data-testid="review-notes"]').element as HTMLTextAreaElement).value).toBe('')
  })

  it('ignores a stale review response when the submission changes mid-flight', async () => {
    let reviewStarted = false
    let resolveReview: (response: HttpResponse<typeof submission>) => void = () => undefined
    server.use(
      http.get('/api/clinical/onboarding/submissions/9', () => HttpResponse.json(submission)),
      http.get('/api/clinical/onboarding/submissions/10', () =>
        HttpResponse.json({ ...submission, id: 10, patientEmail: 'fresh@example.com' })),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/clinical/onboarding/submissions/9/review', () => {
        reviewStarted = true
        return new Promise<HttpResponse<typeof submission>>((resolve) => {
          resolveReview = resolve
        })
      }),
    )
    const wrapper = mount(ClinicalOnboardingReviewPanel, {
      props: { submissionId: 9 },
      global: { plugins: [createPinia(), i18n] },
    })
    await flushPromises()

    await wrapper.find('[data-testid="submit-review"]').trigger('click')
    await vi.waitFor(() => expect(reviewStarted).toBe(true))

    await wrapper.setProps({ submissionId: 10 })
    await flushPromises()
    expect(wrapper.text()).toContain('fresh@example.com')
    expect((wrapper.find('[data-testid="submit-review"]').element as HTMLButtonElement).disabled).toBe(false)

    resolveReview(HttpResponse.json({ ...submission, reviewStatus: 'REVIEWED' }))
    await flushPromises()

    expect(wrapper.text()).toContain('fresh@example.com')
    expect(wrapper.text()).not.toContain('patient@example.com')
    expect(wrapper.emitted('reviewed')).toBeUndefined()
  })

  it('renders lab notes and the review audit details', async () => {
    server.use(
      http.get('/api/clinical/onboarding/submissions/9', () =>
        HttpResponse.json({
          ...submission,
          labNotes: 'fasting blood draw',
          reviewStatus: 'REVIEWED',
          reviewedByEmail: 'doctor@example.com',
          reviewedAt: '2026-08-02T14:00:00Z',
          reviewNotes: 'labs match the history',
        }),
      ),
    )
    const wrapper = mount(ClinicalOnboardingReviewPanel, {
      props: { submissionId: 9 },
      global: { plugins: [createPinia(), i18n] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('fasting blood draw')
    expect(wrapper.text()).toContain('doctor@example.com')
    expect(wrapper.text()).toContain('labs match the history')
    expect(wrapper.find('[data-testid="submit-review"]').exists()).toBe(false)
  })
})
