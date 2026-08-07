<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'

const { t } = useI18n()
const route = useRoute()

const patientProfileId = computed(() => Number(route.params.patientProfileId))
// Identity comes from the server, keyed by the path id — never from the URL query.
const patientEmail = ref<string | null>(null)

let identityGeneration = 0

async function loadIdentity() {
  const gen = ++identityGeneration
  try {
    const option = await clinicalApi.getPatient(patientProfileId.value)
    if (gen !== identityGeneration) return
    patientEmail.value = option.email
  } catch {
    if (gen === identityGeneration) patientEmail.value = null
  }
}

onMounted(loadIdentity)
watch(patientProfileId, loadIdentity)

const tabs = computed(() => {
  const base = `/clinical/patients/${patientProfileId.value}`
  return [
    { to: `${base}/check-ins`, label: t('clinical.tabCheckIns') },
    { to: `${base}/trends`, label: t('clinical.tabTrends') },
    { to: `${base}/labs`, label: t('clinical.tabLabs') },
    { to: `${base}/red-flags`, label: t('clinical.tabRedFlags') },
    { to: `${base}/onboarding`, label: t('clinical.tabOnboarding') },
  ]
})
</script>

<template>
  <section>
    <router-link to="/clinical" class="text-sm text-blue-600 dark:text-blue-400">← {{ t('clinical.backToOverview') }}</router-link>
    <h1 class="mt-2 text-2xl font-semibold">
      {{ patientEmail ?? t('clinical.patientFallback', { id: patientProfileId }) }}
    </h1>
    <nav class="mt-4 flex flex-wrap gap-3 border-b pb-2 text-sm dark:border-gray-700">
      <router-link v-for="tab in tabs" :key="tab.to"
                   :to="tab.to"
                   class="text-gray-700 hover:text-blue-700 dark:text-gray-300 dark:hover:text-blue-300"
                   active-class="font-semibold text-blue-700 dark:text-blue-300">
        {{ tab.label }}
      </router-link>
    </nav>
    <!-- Remount the active tab when the patient changes: child views capture
         patientProfileId once at setup, and a reused child would keep showing
         (or editing) the previous patient's data under the new header. -->
    <router-view :key="patientProfileId" />
  </section>
</template>
