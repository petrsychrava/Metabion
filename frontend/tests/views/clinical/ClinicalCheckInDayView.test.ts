import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalCheckInDayView from '@/views/clinical/ClinicalCheckInDayView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const detail = {
  patientProfileId: 41,
  patientEmail: 'patient@example.com',
  date: '2026-08-03',
  dietLog: {
    id: 7,
    patientProfileId: 41,
    patientEmail: 'patient@example.com',
    logDate: '2026-08-03',
    adherenceLevel: 'MOSTLY',
    appetiteLevel: 'NORMAL',
    notes: 'felt ok',
    metadata: null,
    createdAt: '2026-08-03T08:00:00Z',
    updatedAt: '2026-08-03T08:00:00Z',
    meals: [
      { id: 1, mealType: 'BREAKFAST', foodDescription: 'eggs', notes: null, sortOrder: 1 },
    ],
    deviations: [],
    photoReferences: [
      {
        id: 5,
        mealId: 1,
        originalFilename: 'eggs.jpg',
        contentType: 'image/jpeg',
        sizeBytes: 1234,
        caption: 'breakfast',
        contentUrl: '/api/diet-log-photos/5/content',
        sortOrder: 1,
      },
    ],
    measurements: [
      {
        id: 3,
        patientProfileId: 41,
        dailyDietLogId: 7,
        measurementType: 'KETONE',
        value: 1.8,
        unit: 'MMOL_L',
        measuredAt: '2026-08-03T06:30:00Z',
        context: 'FASTING',
        notes: null,
        metadata: null,
        createdAt: '2026-08-03T06:31:00Z',
      },
    ],
  },
  symptomCheckIn: {
    id: 9,
    patientProfileId: 41,
    questionnaireVersionId: 2,
    checkInDate: '2026-08-03',
    flareState: 'NO_FLARE',
    totalSymptomScore: 4,
    notes: null,
    answers: [
      {
        questionId: 1,
        questionStableKey: 'pain',
        label: 'Abdominal pain',
        answerType: 'SINGLE_CHOICE',
        optionId: 11,
        optionStableKey: 'mild',
        optionLabel: 'Mild',
        answerText: null,
        answerNumeric: null,
        numericScore: 1,
      },
    ],
    createdAt: '2026-08-03T07:00:00Z',
    updatedAt: '2026-08-03T07:00:00Z',
  },
}

describe('ClinicalCheckInDayView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders both halves including the photo and answers', async () => {
    server.use(http.get('/api/clinical/daily-check-ins/41/2026-08-03', () => HttpResponse.json(detail)))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/clinical/patients/:patientProfileId/check-ins/:date', component: ClinicalCheckInDayView }],
    })
    await router.push('/clinical/patients/41/check-ins/2026-08-03')
    const wrapper = mount(ClinicalCheckInDayView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('eggs')
    expect(wrapper.text()).toContain(en.enums.DietAdherenceLevel.MOSTLY)
    expect(wrapper.text()).toContain('Abdominal pain')
    expect(wrapper.text()).toContain('Mild')
    const img = wrapper.find('img')
    expect(img.attributes('src')).toBe('/api/diet-log-photos/5/content')
  })

  it('renders the empty halves when a side is missing', async () => {
    server.use(
      http.get('/api/clinical/daily-check-ins/41/2026-08-03', () =>
        HttpResponse.json({ ...detail, dietLog: null }),
      ),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/clinical/patients/:patientProfileId/check-ins/:date', component: ClinicalCheckInDayView }],
    })
    await router.push('/clinical/patients/41/check-ins/2026-08-03')
    const wrapper = mount(ClinicalCheckInDayView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain(en.clinical.noDietLog)
    expect(wrapper.text()).toContain('Abdominal pain')
  })
})
