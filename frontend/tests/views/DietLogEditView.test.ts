import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../msw/server'
import DietLogEditView from '@/views/DietLogEditView.vue'
import en from '@/i18n/en.json'
import type { DailyDietLogRequest } from '@/types/api'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/diet-logs/:date', component: DietLogEditView }],
  })
}

describe('DietLogEditView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('initializes a blank form when the day has no log (404)', async () => {
    server.use(
      http.get('/api/diet-logs/2026-07-24', () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
    )
    const router = makeRouter()
    await router.push('/diet-logs/2026-07-24')
    const wrapper = mount(DietLogEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.find('select[data-testid="adherence"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid^="meal-row-"]')).toHaveLength(0)
  })

  it('upserts the full log on save', async () => {
    let received: DailyDietLogRequest | null = null
    server.use(
      http.get('/api/diet-logs/2026-07-24', () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/diet-logs', async ({ request }) => {
        received = (await request.json()) as DailyDietLogRequest
        return HttpResponse.json({ id: 1, logDate: '2026-07-24' })
      }),
    )
    const router = makeRouter()
    await router.push('/diet-logs/2026-07-24')
    const wrapper = mount(DietLogEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="add-meal"]').trigger('click')
    await wrapper.find('input[data-testid="meal-desc-0"]').setValue('Eggs and avocado')
    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(received).not.toBeNull()
    expect(received!.logDate).toBe('2026-07-24')
    expect(received!.meals).toHaveLength(1)
    expect(received!.meals[0].foodDescription).toBe('Eggs and avocado')
    expect(wrapper.text()).toContain(en.common.saved)
  })

  it('saves a deviation with the index of its meal, never null', async () => {
    let received: DailyDietLogRequest | null = null
    server.use(
      http.get('/api/diet-logs/2026-07-24', () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/diet-logs', async ({ request }) => {
        received = (await request.json()) as DailyDietLogRequest
        return HttpResponse.json({ id: 1, logDate: '2026-07-24' })
      }),
    )
    const router = makeRouter()
    await router.push('/diet-logs/2026-07-24')
    const wrapper = mount(DietLogEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.find('[data-testid="add-deviation"]').attributes()).toHaveProperty('disabled')
    await wrapper.find('[data-testid="add-meal"]').trigger('click')
    await wrapper.find('[data-testid="add-deviation"]').trigger('click')
    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(received).not.toBeNull()
    expect(received!.deviations).toHaveLength(1)
    expect(received!.deviations[0].mealIndex).toBe(0)
  })

  it('maps loaded photo references to their meal index on re-save', async () => {
    let received: DailyDietLogRequest | null = null
    server.use(
      http.get('/api/diet-logs/2026-07-24', () => HttpResponse.json({
        id: 1,
        patientProfileId: 1,
        patientEmail: 'p@example.com',
        logDate: '2026-07-24',
        adherenceLevel: 'FULL',
        appetiteLevel: 'NORMAL',
        notes: null,
        metadata: null,
        createdAt: '2026-07-24T08:00:00Z',
        updatedAt: '2026-07-24T08:00:00Z',
        meals: [{ id: 5, mealType: 'BREAKFAST', foodDescription: 'Eggs', notes: null, sortOrder: 0 }],
        deviations: [],
        photoReferences: [{
          id: 9, mealId: 5, originalFilename: 'eggs.jpg', contentType: 'image/jpeg',
          sizeBytes: 100, caption: null, contentUrl: '/api/diet-photos/9/content', sortOrder: 0,
        }],
        measurements: [],
      })),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/diet-logs', async ({ request }) => {
        received = (await request.json()) as DailyDietLogRequest
        return HttpResponse.json({ id: 1, logDate: '2026-07-24' })
      }),
    )
    const router = makeRouter()
    await router.push('/diet-logs/2026-07-24')
    const wrapper = mount(DietLogEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.find('[data-testid="photo-meal-0"]').exists()).toBe(true)
    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(received).not.toBeNull()
    expect(received!.photoReferences).toHaveLength(1)
    expect(received!.photoReferences[0].uploadId).toBe(9)
    expect(received!.photoReferences[0].mealIndex).toBe(0)
  })

  it('preserves log and measurement metadata on re-save', async () => {
    let received: DailyDietLogRequest | null = null
    server.use(
      http.get('/api/diet-logs/2026-07-24', () => HttpResponse.json({
        id: 1,
        patientProfileId: 1,
        patientEmail: 'p@example.com',
        logDate: '2026-07-24',
        adherenceLevel: 'FULL',
        appetiteLevel: 'NORMAL',
        notes: null,
        metadata: '{"source":"mcp"}',
        createdAt: '2026-07-24T08:00:00Z',
        updatedAt: '2026-07-24T08:00:00Z',
        meals: [{ id: 5, mealType: 'BREAKFAST', foodDescription: 'Eggs', notes: null, sortOrder: 0 }],
        deviations: [],
        photoReferences: [],
        measurements: [{
          id: 7, patientProfileId: 1, dailyDietLogId: 1,
          measurementType: 'GLUCOSE', value: 5.2, unit: 'MMOL_L',
          measuredAt: '2026-07-24T07:00:00Z', context: 'FASTING',
          notes: null, metadata: '{"device":"dexcom"}', createdAt: '2026-07-24T07:00:00Z',
        }],
      })),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/diet-logs', async ({ request }) => {
        received = (await request.json()) as DailyDietLogRequest
        return HttpResponse.json({ id: 1, logDate: '2026-07-24' })
      }),
    )
    const router = makeRouter()
    await router.push('/diet-logs/2026-07-24')
    const wrapper = mount(DietLogEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(received).not.toBeNull()
    expect(received!.metadata).toBe('{"source":"mcp"}')
    expect(received!.measurements).toHaveLength(1)
    expect(received!.measurements[0].metadata).toBe('{"device":"dexcom"}')
  })

  it('shows an error and withholds the editor when loading fails (non-404)', async () => {
    server.use(
      http.get('/api/diet-logs/2026-07-24', () => HttpResponse.json({ error: 'request_failed' }, { status: 500 })),
    )
    const router = makeRouter()
    await router.push('/diet-logs/2026-07-24')
    const wrapper = mount(DietLogEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.request_failed)
    expect(wrapper.find('[data-testid="save"]').exists()).toBe(false)
  })

  it('defaults a new measurement into the edited log date, not the current instant', async () => {
    let received: DailyDietLogRequest | null = null
    server.use(
      http.get('/api/account/profile', () => HttpResponse.json({
        dateOfBirth: '1990-01-01',
        sex: 'PREFER_NOT_TO_SAY',
        countryRegion: 'CZ',
        timezone: 'UTC',
      })),
      http.get('/api/diet-logs/2026-07-24', () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/diet-logs', async ({ request }) => {
        received = (await request.json()) as DailyDietLogRequest
        return HttpResponse.json({ id: 1, logDate: '2026-07-24' })
      }),
    )
    const router = makeRouter()
    await router.push('/diet-logs/2026-07-24')
    const wrapper = mount(DietLogEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="add-measurement"]').trigger('click')
    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(received).not.toBeNull()
    expect(received!.measurements).toHaveLength(1)
    // 2026-07-24 is historical: the default must be noon of that day in the
    // patient timezone, or the backend rejects the save.
    expect(received!.measurements[0].measuredAt).toBe('2026-07-24T12:00:00.000Z')
  })

  it('restricts ketone measurements to MMOL_L', async () => {
    server.use(
      http.get('/api/diet-logs/2026-07-24', () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
    )
    const router = makeRouter()
    await router.push('/diet-logs/2026-07-24')
    const wrapper = mount(DietLogEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="add-measurement"]').trigger('click')
    const unitSelect = wrapper.find('[data-testid="measurement-unit-0"]')
    expect(unitSelect.findAll('option')).toHaveLength(2)

    await wrapper.find('[data-testid="measurement-type-0"]').setValue('KETONE')
    const ketoneUnitSelect = wrapper.find('[data-testid="measurement-unit-0"]')
    expect(ketoneUnitSelect.findAll('option')).toHaveLength(1)
    expect((ketoneUnitSelect.element as HTMLSelectElement).value).toBe('MMOL_L')
  })
})
