import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import ClinicalPatientWorkspaceView from '@/views/clinical/ClinicalPatientWorkspaceView.vue'
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
})
