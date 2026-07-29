import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../msw/server'
import DietLogHistoryView from '@/views/DietLogHistoryView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/diet-logs', component: DietLogHistoryView }],
  })
}

describe('DietLogHistoryView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('blocks a range over 370 days without calling the API', async () => {
    let listCalls = 0
    server.use(
      http.get('/api/diet-logs', () => {
        listCalls += 1
        return HttpResponse.json([])
      }),
    )
    const router = makeRouter()
    await router.push('/diet-logs')
    const wrapper = mount(DietLogHistoryView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(listCalls).toBe(1)

    const [fromInput, toInput] = wrapper.findAll('input[type="date"]')
    await fromInput.setValue('2024-01-01')
    await toInput.setValue('2025-01-06')
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain(en.errors.date_range_too_long)
    expect(listCalls).toBe(1)
    expect(wrapper.findAll('tbody tr')).toHaveLength(0)
  })

  it('shows the invalid-range message when from is after to', async () => {
    let listCalls = 0
    server.use(
      http.get('/api/diet-logs', () => {
        listCalls += 1
        return HttpResponse.json([])
      }),
    )
    const router = makeRouter()
    await router.push('/diet-logs')
    const wrapper = mount(DietLogHistoryView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    const [fromInput, toInput] = wrapper.findAll('input[type="date"]')
    await fromInput.setValue('2025-02-01')
    await toInput.setValue('2025-01-01')
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain(en.errors.date_range_invalid)
    expect(listCalls).toBe(1)
  })
})
