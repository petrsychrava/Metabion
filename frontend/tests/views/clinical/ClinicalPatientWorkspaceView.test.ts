import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { h } from 'vue'
import { server } from '../../msw/server'
import ClinicalPatientWorkspaceView from '@/views/clinical/ClinicalPatientWorkspaceView.vue'
import ClinicalCheckInsView from '@/views/clinical/ClinicalCheckInsView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

function makeRouter() {
  const stub = { template: '<div />' }
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical', component: stub },
      {
        path: '/clinical/patients/:patientProfileId',
        component: ClinicalPatientWorkspaceView,
        children: ['check-ins', 'trends', 'labs', 'red-flags', 'onboarding'].map((path) => ({
          path,
          component: stub,
        })),
      },
    ],
  })
}

describe('ClinicalPatientWorkspaceView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('resolves the header identity from the server and renders plain tab links', async () => {
    server.use(
      http.get('/api/clinical/patients/41', () =>
        HttpResponse.json({ id: 41, email: 'patient@example.com' }),
      ),
    )
    const router = makeRouter()
    await router.push('/clinical/patients/41/check-ins')
    const wrapper = mount(ClinicalPatientWorkspaceView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('patient@example.com')
    const html = wrapper.html()
    expect(html).toContain('href="/clinical/patients/41/trends"')
    expect(html).toContain('href="/clinical/patients/41/red-flags"')
    expect(html).toContain('href="/clinical"')
  })

  it('falls back to a localized patient label when the identity cannot be loaded', async () => {
    server.use(
      http.get('/api/clinical/patients/41', () =>
        HttpResponse.json({ error: 'forbidden' }, { status: 403 }),
      ),
    )
    const router = makeRouter()
    await router.push('/clinical/patients/41/check-ins')
    const wrapper = mount(ClinicalPatientWorkspaceView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain(en.clinical.patientFallback.replace('{id}', '41'))
  })

  it('remounts the child tab and reloads the identity when the patient id changes', async () => {
    const requested: (string | null)[] = []
    server.use(
      http.get('/api/clinical/daily-check-ins', ({ request }) => {
        requested.push(new URL(request.url).searchParams.get('patientProfileId'))
        return HttpResponse.json([])
      }),
      http.get('/api/clinical/patients/41', () => HttpResponse.json({ id: 41, email: 'a@example.com' })),
      http.get('/api/clinical/patients/42', () => HttpResponse.json({ id: 42, email: 'b@example.com' })),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/clinical', component: { template: '<div />' } },
        {
          path: '/clinical/patients/:patientProfileId',
          component: ClinicalPatientWorkspaceView,
          children: [
            { path: 'check-ins', component: ClinicalCheckInsView },
            // Stubs so the workspace's tab links resolve without warnings.
            { path: 'trends', component: { template: '<div />' } },
            { path: 'labs', component: { template: '<div />' } },
            { path: 'red-flags', component: { template: '<div />' } },
            { path: 'onboarding', component: { template: '<div />' } },
          ],
        },
      ],
    })
    const wrapper = mount({ render: () => h(RouterView) }, { global: { plugins: [createPinia(), i18n, router] } })

    await router.push('/clinical/patients/41/check-ins')
    await flushPromises()
    expect(requested).toEqual(['41'])
    expect(wrapper.text()).toContain('a@example.com')

    await router.push('/clinical/patients/42/check-ins')
    await flushPromises()
    expect(requested).toEqual(['41', '42'])
    expect(wrapper.text()).toContain('b@example.com')
  })
})
