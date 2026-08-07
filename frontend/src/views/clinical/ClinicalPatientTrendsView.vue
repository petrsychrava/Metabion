<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { useApiError } from '@/composables/useApiError'
import { dateRangeError } from '@/utils/dateRange'
import { convertGlucose } from '@/utils/glucose'
import LineChart from '@/components/LineChart.vue'
import type { DailyTrendResponse } from '@/types/api'

const { t } = useI18n()
const route = useRoute()
const { message, capture, clear } = useApiError()

const patientProfileId = Number(route.params.patientProfileId)

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const monthAgo = new Date(today)
monthAgo.setDate(monthAgo.getDate() - 30)

const from = ref(iso(monthAgo))
const to = ref(iso(today))
const trend = ref<DailyTrendResponse | null>(null)
const loading = ref(true)

let loadGeneration = 0

async function load() {
  clear()
  // Bump before any early return: a range error also invalidates an in-flight request.
  const gen = ++loadGeneration
  const rangeError = dateRangeError(from.value, to.value)
  if (rangeError) {
    message.value = t(`errors.date_range_${rangeError === 'too_long' ? 'too_long' : 'invalid'}`)
    // The bump above bars the in-flight request from clearing this — do it here.
    loading.value = false
    return
  }
  loading.value = true
  try {
    const result = await clinicalApi.dailyTrend(patientProfileId, from.value, to.value)
    if (gen !== loadGeneration) return
    trend.value = result
  } catch (e) {
    if (gen !== loadGeneration) return
    // Drop the previous charts: the controls describe the failed request now.
    trend.value = null
    capture(e)
  } finally {
    if (gen === loadGeneration) loading.value = false
  }
}

onMounted(load)

const labels = computed(() => trend.value?.days.map((d) => d.date) ?? [])

const symptomDataset = computed(() => [
  { label: t('trends.symptomScore'), data: trend.value?.days.map((d) => d.symptomScore) ?? [] },
])

function measurementData(kind: 'glucoseMeasurements' | 'ketoneMeasurements') {
  // Average per day when multiple measurements exist. The backend returns each
  // glucose point in its own unit, so normalize to the trend unit first.
  return trend.value?.days.map((d) => {
    const points = d[kind]
    if (points.length === 0) return null
    const target = trend.value!.glucoseUnit
    return points.reduce((sum, p) => sum + (kind === 'glucoseMeasurements' ? convertGlucose(p.value, p.unit, target) : p.value), 0) / points.length
  }) ?? []
}
</script>

<template>
  <section class="mt-4">
    <div class="flex flex-wrap items-end gap-3">
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
    <template v-else-if="trend">
      <div class="mt-6 rounded border bg-white p-4 dark:bg-gray-800">
        <h3 class="mb-2 font-medium">{{ t('trends.symptomScore') }}</h3>
        <LineChart :labels="labels" :datasets="symptomDataset" />
      </div>
      <div class="mt-6 rounded border bg-white p-4 dark:bg-gray-800">
        <h3 class="mb-2 font-medium">
          {{ t('trends.glucose') }} ({{ t(`enums.MeasurementUnit.${trend.glucoseUnit}`) }})
        </h3>
        <LineChart :labels="labels" :datasets="[{ label: t('trends.glucose'), data: measurementData('glucoseMeasurements') }]" />
      </div>
      <div class="mt-6 rounded border bg-white p-4 dark:bg-gray-800">
        <h3 class="mb-2 font-medium">{{ t('trends.ketones') }}</h3>
        <LineChart :labels="labels" :datasets="[{ label: t('trends.ketones'), data: measurementData('ketoneMeasurements') }]" />
      </div>
    </template>
  </section>
</template>
