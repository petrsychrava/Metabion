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
})
