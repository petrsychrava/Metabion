import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../msw/server'
import CheckInEditView from '@/views/CheckInEditView.vue'
import en from '@/i18n/en.json'
import type { SymptomCheckInRequest, SymptomQuestionnaire } from '@/types/api'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const questionnaire: SymptomQuestionnaire = {
  id: 1,
  stableKey: 'daily',
  displayName: 'Daily symptoms',
  versionId: 11,
  versionNumber: 3,
  questions: [
    {
      id: 101, stableKey: 'pain', label: 'Abdominal pain', helpText: null,
      answerType: 'SINGLE_CHOICE', required: true,
      minNumericValue: null, maxNumericValue: null,
      options: [
        { id: 1001, stableKey: 'none', label: 'None', numericScore: 0 },
        { id: 1002, stableKey: 'severe', label: 'Severe', numericScore: 3 },
      ],
    },
    {
      id: 102, stableKey: 'stools', label: 'Stool count', helpText: null,
      answerType: 'NUMERIC', required: true,
      minNumericValue: 0, maxNumericValue: 20, options: [],
    },
  ],
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/check-ins/:date', component: CheckInEditView }],
  })
}

describe('CheckInEditView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders questionnaire questions by answer type', async () => {
    server.use(
      http.get('/api/symptom-questionnaires/active', () => HttpResponse.json(questionnaire)),
      http.get('/api/symptom-check-ins/2026-07-24', () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
    )
    const router = makeRouter()
    await router.push('/check-ins/2026-07-24')
    const wrapper = mount(CheckInEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.text()).toContain('Abdominal pain')
    expect(wrapper.findAll('input[type="radio"]')).toHaveLength(2)
    expect(wrapper.find('input[type="number"]').exists()).toBe(true)
  })

  it('submits answers with the questionnaire version id', async () => {
    let received: SymptomCheckInRequest | null = null
    server.use(
      http.get('/api/symptom-questionnaires/active', () => HttpResponse.json(questionnaire)),
      http.get('/api/symptom-check-ins/2026-07-24', () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/symptom-check-ins', async ({ request }) => {
        received = (await request.json()) as SymptomCheckInRequest
        return HttpResponse.json({ id: 1 })
      }),
    )
    const router = makeRouter()
    await router.push('/check-ins/2026-07-24')
    const wrapper = mount(CheckInEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.findAll('input[type="radio"]')[1].setValue(true)
    await wrapper.find('input[type="number"]').setValue(4)
    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(received).not.toBeNull()
    expect(received!.questionnaireVersionId).toBe(11)
    expect(received!.checkInDate).toBe('2026-07-24')
    expect(received!.answers).toContainEqual({ questionId: 101, optionId: 1002, answerText: null, answerNumeric: null })
    expect(received!.answers).toContainEqual({ questionId: 102, optionId: null, answerText: null, answerNumeric: 4 })
  })

  it('sends null instead of an empty string when a numeric answer is typed then cleared', async () => {
    let received: SymptomCheckInRequest | null = null
    server.use(
      http.get('/api/symptom-questionnaires/active', () => HttpResponse.json(questionnaire)),
      http.get('/api/symptom-check-ins/2026-07-24', () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/symptom-check-ins', async ({ request }) => {
        received = (await request.json()) as SymptomCheckInRequest
        return HttpResponse.json({ id: 1 })
      }),
    )
    const router = makeRouter()
    await router.push('/check-ins/2026-07-24')
    const wrapper = mount(CheckInEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.findAll('input[type="radio"]')[1].setValue(true)
    const numeric = wrapper.find('input[type="number"]')
    await numeric.setValue(4)
    await numeric.setValue('')
    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(received).not.toBeNull()
    const cleared = received!.answers.find((a) => a.questionId === 102)
    expect(cleared?.answerNumeric ?? null).toBeNull()
    expect(JSON.stringify(received!.answers)).not.toContain('""')
  })

  it('shows an error and withholds the editor when the existing check-in fails to load (non-404)', async () => {
    server.use(
      http.get('/api/symptom-questionnaires/active', () => HttpResponse.json(questionnaire)),
      http.get('/api/symptom-check-ins/2026-07-24', () => HttpResponse.json({ error: 'request_failed' }, { status: 500 })),
    )
    const router = makeRouter()
    await router.push('/check-ins/2026-07-24')
    const wrapper = mount(CheckInEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.request_failed)
    expect(wrapper.find('[data-testid="save"]').exists()).toBe(false)
  })
})
