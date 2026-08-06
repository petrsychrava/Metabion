<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { useApiError } from '@/composables/useApiError'
import { dateRangeError } from '@/utils/dateRange'
import type { ClinicalDailyCheckInSummary } from '@/types/api'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { message, capture, clear } = useApiError()

const patientProfileId = Number(route.params.patientProfileId)

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const weekAgo = new Date(today)
weekAgo.setDate(weekAgo.getDate() - 6) // 7-day default, like the Thymeleaf page

const from = ref(iso(weekAgo))
const to = ref(iso(today))
const items = ref<ClinicalDailyCheckInSummary[]>([])
const loading = ref(true)

let loadGeneration = 0

async function load() {
  clear()
  // Bump before any early return: a range error also invalidates an in-flight request.
  const gen = ++loadGeneration
  const rangeError = dateRangeError(from.value, to.value)
  if (rangeError) {
    message.value = t(`errors.date_range_${rangeError === 'too_long' ? 'too_long' : 'invalid'}`)
    return
  }
  loading.value = true
  try {
    const result = await clinicalApi.listDailyCheckIns(patientProfileId, from.value, to.value)
    if (gen !== loadGeneration) return
    items.value = result
  } catch (e) {
    if (gen !== loadGeneration) return
    capture(e)
  } finally {
    if (gen === loadGeneration) loading.value = false
  }
}

function open(item: ClinicalDailyCheckInSummary) {
  void router.push({
    path: `/clinical/patients/${patientProfileId}/check-ins/${item.date}`,
    query: route.query,
  })
}

onMounted(load)
</script>

<template>
  <section class="mt-4">
    <h2 class="text-lg font-medium">{{ t('clinical.checkInsTitle') }}</h2>
    <div class="mt-2 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('common.from') }}
        <input v-model="from" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <label class="text-sm">{{ t('common.to') }}
        <input v-model="to" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <button class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="load">{{ t('common.apply') }}</button>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <table v-else data-testid="checkins-table" class="mt-4 w-full border-collapse bg-white text-sm dark:bg-gray-800">
      <thead>
        <tr class="border-b text-left">
          <th class="p-2">{{ t('clinical.colDate') }}</th>
          <th class="p-2">{{ t('clinical.colDietLog') }}</th>
          <th class="p-2">{{ t('clinical.colSymptomCheckIn') }}</th>
          <th class="p-2">{{ t('clinical.colScore') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.date" data-testid="checkin-row"
            class="cursor-pointer border-b hover:bg-gray-50 dark:hover:bg-gray-700"
            @click="open(item)">
          <td class="p-2">{{ item.date }}</td>
          <td class="p-2">
            {{ item.adherenceLevel ? t(`enums.DietAdherenceLevel.${item.adherenceLevel}`) : t('clinical.noValue') }}
          </td>
          <td class="p-2">
            {{ item.flareState ? t(`checkIn.FlareState.${item.flareState}`) : t('clinical.noValue') }}
          </td>
          <td class="p-2">{{ item.symptomScore ?? t('clinical.noValue') }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
