import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalOnboardingQueueView from '@/views/clinical/ClinicalOnboardingQueueView.vue'
import ClinicalOnboardingReviewView from '@/views/clinical/ClinicalOnboardingReviewView.vue'
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
  dateOfBirth: '1990-05-12',
  sex: 'FEMALE',
  countryRegion: 'CZ',
  timezone: 'Europe/Prague',
  diagnosisType: 'CROHNS_DISEASE',
  diagnosisYear: 2019,
  diseaseLocation: 'ileum',
  diseaseBehavior: 'inflammatory',
  activityEstimate: 'MILD',
  currentMedications: 'mesalazine',
  steroidUse: 'NONE',
  advancedTherapyExposure: 'NEVER_USED',
  medicationNotes: null,
  labsCollectedAt: '2026-07-20',
  crpMgL: 3.2,
  fecalCalprotectinUgG: 180,
  hemoglobinGDl: 13.5,
  albuminGDl: 4.1,
  labNotes: null,
  reviewStatus: 'PENDING_REVIEW',
}

const summary = {
  id: 9,
  patientProfileId: 41,
  patientEmail: 'patient@example.com',
  onboardingContext: 'baseline',
  version: 1,
  submittedAt: '2026-08-01T09:00:00Z',
  diagnosisType: 'CROHNS_DISEASE',
  reviewStatus: 'PENDING_REVIEW',
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical/onboarding', component: ClinicalOnboardingQueueView },
      { path: '/clinical/onboarding/:submissionId', component: ClinicalOnboardingReviewView },
    ],
  })
}

describe('clinical onboarding review', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('lists the queue with a status filter and opens the review view', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/clinical/onboarding/submissions', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json([summary])
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/onboarding')
    const wrapper = mount(ClinicalOnboardingQueueView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('patient@example.com')
    await wrapper.find('[data-testid="status-filter"]').setValue('PENDING_REVIEW')
    await wrapper.find('[data-testid="apply-filter"]').trigger('click')
    await flushPromises()
    expect(seenUrl).toContain('status=PENDING_REVIEW')

    await wrapper.find('[data-testid="queue-row"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/clinical/onboarding/9')
  })

  it('shows an empty state instead of the table when no submissions match', async () => {
    server.use(
      http.get('/api/clinical/onboarding/submissions', () => HttpResponse.json([])),
    )
    const router = makeRouter()
    await router.push('/clinical/onboarding')
    const wrapper = mount(ClinicalOnboardingQueueView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain(en.clinical.queueEmpty)
    expect(wrapper.find('table').exists()).toBe(false)
  })

  it('renders the submission and submits a review', async () => {
    let received: unknown
    server.use(
      http.get('/api/clinical/onboarding/submissions/9', () => HttpResponse.json(submission)),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/clinical/onboarding/submissions/9/review', async ({ request }) => {
        received = await request.json()
        return HttpResponse.json({ ...submission, reviewStatus: 'REVIEWED' })
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/onboarding/9')
    const wrapper = mount(ClinicalOnboardingReviewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain(en.onboarding.diagnosis.CROHNS_DISEASE)
    expect(wrapper.text()).toContain('mesalazine')

    await wrapper.find('[data-testid="review-notes"]').setValue('looks fine')
    await wrapper.find('[data-testid="submit-review"]').trigger('click')
    await flushPromises()

    expect(received).toEqual({ reviewStatus: 'REVIEWED', reviewNotes: 'looks fine' })
    expect(wrapper.find('[data-testid="submit-review"]').exists()).toBe(false)
    expect(wrapper.text()).toContain(en.onboarding.reviewStatus.REVIEWED)
  })

  it('hides the decision form for an already-reviewed submission', async () => {
    server.use(
      http.get('/api/clinical/onboarding/submissions/9', () =>
        HttpResponse.json({ ...submission, reviewStatus: 'NEEDS_FOLLOW_UP' }),
      ),
    )
    const router = makeRouter()
    await router.push('/clinical/onboarding/9')
    const wrapper = mount(ClinicalOnboardingReviewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.find('[data-testid="submit-review"]').exists()).toBe(false)
    expect(wrapper.text()).toContain(en.clinical.alreadyReviewed)
  })

  it('drops a stale queue response when two filter applies race', async () => {
    const pendingRows = [summary]
    const reviewedRows = [{ ...summary, id: 10, patientEmail: 'reviewed@example.com', reviewStatus: 'REVIEWED' }]
    let call = 0
    let resolveHeld: (response: HttpResponse<typeof pendingRows>) => void = () => undefined
    server.use(
      http.get('/api/clinical/onboarding/submissions', () => {
        call += 1
        if (call === 1) return HttpResponse.json([])
        // The first Apply stays in flight until the test releases it.
        if (call === 2) {
          return new Promise<HttpResponse<typeof pendingRows>>((resolve) => {
            resolveHeld = resolve
          })
        }
        return HttpResponse.json(reviewedRows)
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/onboarding')
    const wrapper = mount(ClinicalOnboardingQueueView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="status-filter"]').setValue('PENDING_REVIEW')
    await wrapper.find('[data-testid="apply-filter"]').trigger('click')
    await wrapper.find('[data-testid="status-filter"]').setValue('REVIEWED')
    await wrapper.find('[data-testid="apply-filter"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('reviewed@example.com')

    resolveHeld(HttpResponse.json(pendingRows))
    await flushPromises()
    expect(wrapper.text()).toContain('reviewed@example.com')
    expect(wrapper.text()).not.toContain('patient@example.com')
  })

  it('clears the previous rows when a replacement filter request fails', async () => {
    let call = 0
    server.use(
      http.get('/api/clinical/onboarding/submissions', () => {
        call += 1
        if (call === 1) return HttpResponse.json([summary])
        return HttpResponse.json({ error: 'request_failed' }, { status: 500 })
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/onboarding')
    const wrapper = mount(ClinicalOnboardingQueueView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.findAll('[data-testid="queue-row"]')).toHaveLength(1)

    await wrapper.find('[data-testid="status-filter"]').setValue('PENDING_REVIEW')
    await wrapper.find('[data-testid="apply-filter"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.request_failed)
    expect(wrapper.findAll('[data-testid="queue-row"]')).toHaveLength(0)
  })
})
