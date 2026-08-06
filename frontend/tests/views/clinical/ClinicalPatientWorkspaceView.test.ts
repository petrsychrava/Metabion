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

  it('renders the patient email header and query-preserving tabs', async () => {
    const router = makeRouter()
    await router.push('/clinical/patients/41/check-ins?email=patient%40example.com')
    const wrapper = mount(ClinicalPatientWorkspaceView, { global: { plugins: [createPinia(), i18n, router] } })

    expect(wrapper.text()).toContain('patient@example.com')
    const html = wrapper.html()
    // vue-router 4 serializes query values with encodeURI, which leaves '@' unencoded
    expect(html).toContain('href="/clinical/patients/41/trends?email=patient@example.com"')
    expect(html).toContain('href="/clinical/patients/41/red-flags?email=patient@example.com"')
    expect(html).toContain('href="/clinical"')
  })

  it('falls back to a localized patient label without the email param', async () => {
    const router = makeRouter()
    await router.push('/clinical/patients/41/check-ins')
    const wrapper = mount(ClinicalPatientWorkspaceView, { global: { plugins: [createPinia(), i18n, router] } })

    expect(wrapper.text()).toContain(en.clinical.patientFallback.replace('{id}', '41'))
  })

  it('remounts the child tab when the patient id changes', async () => {
    const requested: (string | null)[] = []
    server.use(
      http.get('/api/clinical/daily-check-ins', ({ request }) => {
        requested.push(new URL(request.url).searchParams.get('patientProfileId'))
        return HttpResponse.json([])
      }),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/clinical', component: { template: '<div />' } },
        {
          path: '/clinical/patients/:patientProfileId',
          component: ClinicalPatientWorkspaceView,
          children: [{ path: 'check-ins', component: ClinicalCheckInsView }],
        },
      ],
    })
    const wrapper = mount({ render: () => h(RouterView) }, { global: { plugins: [createPinia(), i18n, router] } })

    await router.push('/clinical/patients/41/check-ins?email=a%40example.com')
    await flushPromises()
    expect(requested).toEqual(['41'])

    await router.push('/clinical/patients/42/check-ins?email=b%40example.com')
    await flushPromises()
    expect(requested).toEqual(['41', '42'])
    expect(wrapper.text()).toContain('b@example.com')
  })
})
