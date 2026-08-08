<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { useApiError } from '@/composables/useApiError'
import { severityBadgeClass } from '@/utils/redFlags'
import type { ClinicalPatientOverview, FlareState, RedFlagSeverity } from '@/types/api'

const { t, locale } = useI18n()
const { message, capture } = useApiError()
const router = useRouter()

const rows = ref<ClinicalPatientOverview[]>([])
const loading = ref(true)

function isStale(row: ClinicalPatientOverview): boolean {
  return row.stale
}

const SEVERITY_RANK: Record<RedFlagSeverity, number> = {
  EMERGENCY: 0,
  URGENT_REVIEW: 1,
  ROUTINE_REVIEW: 2,
}
const FLARE_RANK: Partial<Record<FlareState, number>> = {
  ACTIVE_FLARE: 3,
  SUSPECTED_FLARE: 4,
}

function rank(row: ClinicalPatientOverview): number {
  if (row.highestRedFlagSeverity) return SEVERITY_RANK[row.highestRedFlagSeverity]
  if (row.latestFlareState && row.latestFlareState !== 'NO_FLARE') return FLARE_RANK[row.latestFlareState] ?? 5
  if (isStale(row)) return 6
  return 7
}

const sortedRows = computed(() =>
  [...rows.value].sort((a, b) => rank(a) - rank(b) || a.patientEmail.localeCompare(b.patientEmail)),
)

function ketones(row: ClinicalPatientOverview): string {
  if (row.latestKetoneValue === null || row.latestKetoneUnit === null) return t('clinical.noValue')
  const value = `${row.latestKetoneValue} ${t(`enums.MeasurementUnit.${row.latestKetoneUnit}`)}`
  if (row.latestKetoneMeasuredAt === null) return value
  return `${value} · ${new Date(row.latestKetoneMeasuredAt).toLocaleDateString(locale.value)}`
}

function open(row: ClinicalPatientOverview) {
  void router.push({ path: `/clinical/patients/${row.patientProfileId}/check-ins` })
}

onMounted(async () => {
  try {
    rows.value = await clinicalApi.overview()
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('clinical.overviewTitle') }}</h1>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else>
      <div v-if="sortedRows.length === 0" class="mt-4">
        <p class="text-sm text-gray-600 dark:text-gray-400">{{ t('clinical.overviewEmpty') }}</p>
        <router-link to="/clinical/onboarding" class="mt-2 inline-block text-sm text-blue-600 dark:text-blue-400">
          {{ t('clinical.overviewQueueLink') }}
        </router-link>
      </div>
      <table v-else class="mt-4 w-full border-collapse bg-white text-sm dark:bg-gray-800">
        <thead>
          <tr class="border-b text-left">
            <th class="p-2">{{ t('clinical.colPatient') }}</th>
            <th class="p-2">{{ t('clinical.colRedFlags') }}</th>
            <th class="p-2">{{ t('clinical.colFlare') }}</th>
            <th class="p-2">{{ t('clinical.colKetones') }}</th>
            <th class="p-2">{{ t('clinical.colAdherence') }}</th>
            <th class="p-2">{{ t('clinical.colLastActivity') }}</th>
            <th class="p-2">{{ t('clinical.colOnboarding') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in sortedRows" :key="row.patientProfileId"
              data-testid="overview-row" :data-email="row.patientEmail"
              class="cursor-pointer border-b hover:bg-gray-50 dark:hover:bg-gray-700"
              @click="open(row)">
            <td class="p-2">{{ row.patientEmail }}</td>
            <td class="p-2">
              <span v-if="row.highestRedFlagSeverity" class="rounded px-2 py-0.5"
                    :class="severityBadgeClass(row.highestRedFlagSeverity)">
                {{ t(`redFlags.severity.${row.highestRedFlagSeverity}`) }} ({{ row.currentRedFlagCount }})
              </span>
              <span v-else>{{ t('clinical.noValue') }}</span>
            </td>
            <td class="p-2">
              <template v-if="row.latestFlareState">
                {{ t(`checkIn.FlareState.${row.latestFlareState}`) }}<template v-if="row.latestSymptomScore !== null"> · {{ row.latestSymptomScore }}</template><template v-if="row.latestSymptomCheckInDate"> · {{ row.latestSymptomCheckInDate }}</template>
              </template>
              <span v-else>{{ t('clinical.noValue') }}</span>
            </td>
            <td class="p-2">{{ ketones(row) }}</td>
            <td class="p-2">
              {{ row.latestAdherenceLevel ? t(`enums.DietAdherenceLevel.${row.latestAdherenceLevel}`) : t('clinical.noValue') }}
            </td>
            <td class="p-2">
              {{ row.lastActivityDate ?? t('clinical.noValue') }}
              <span v-if="isStale(row)"
                    class="ml-1 rounded bg-amber-100 px-2 py-0.5 text-amber-800 dark:bg-amber-950 dark:text-amber-200">
                {{ t('clinical.stale') }}
              </span>
            </td>
            <td class="p-2">
              <span v-if="row.pendingOnboardingCount > 0"
                    class="rounded bg-blue-100 px-2 py-0.5 text-blue-800 dark:bg-blue-950 dark:text-blue-200">
                {{ t('clinical.pendingReviews', { count: row.pendingOnboardingCount }) }}
              </span>
              <span v-else>{{ t('clinical.noValue') }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </template>
  </section>
</template>
