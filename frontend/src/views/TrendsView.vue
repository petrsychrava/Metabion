<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { symptomApi } from '@/api/symptoms'
import { useApiError } from '@/composables/useApiError'
import { dateRangeError } from '@/utils/dateRange'
import LineChart from '@/components/LineChart.vue'
import type { DailyTrendResponse } from '@/types/api'

const { t } = useI18n()
const { message, capture, clear } = useApiError()

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

async function load() {
  clear()
  const rangeError = dateRangeError(from.value, to.value)
  if (rangeError) {
    message.value = t(`errors.date_range_${rangeError === 'too_long' ? 'too_long' : 'invalid'}`)
    return
  }
  loading.value = true
  try {
    trend.value = await symptomApi.dailyTrend(from.value, to.value)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

const labels = computed(() => trend.value?.days.map((d) => d.date) ?? [])

const symptomDataset = computed(() => [
  { label: t('trends.symptomScore'), data: trend.value?.days.map((d) => d.symptomScore) ?? [] },
])

function measurementData(kind: 'glucoseMeasurements' | 'ketoneMeasurements') {
  // Average per day when multiple measurements exist.
  return trend.value?.days.map((d) => {
    const points = d[kind]
    if (points.length === 0) return null
    return points.reduce((sum, p) => sum + p.value, 0) / points.length
  }) ?? []
}
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('nav.trends') }}</h1>
    <div class="mt-4 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('common.from') }}
        <input v-model="from" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1" />
      </label>
      <label class="text-sm">{{ t('common.to') }}
        <input v-model="to" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1" />
      </label>
      <button class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="load">{{ t('common.apply') }}</button>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else-if="trend">
      <div class="mt-6 rounded border bg-white p-4">
        <h2 class="mb-2 font-medium">{{ t('trends.symptomScore') }}</h2>
        <LineChart :labels="labels" :datasets="symptomDataset" />
      </div>
      <div class="mt-6 rounded border bg-white p-4">
        <h2 class="mb-2 font-medium">
          {{ t('trends.glucose') }} ({{ t(`enums.MeasurementUnit.${trend.glucoseUnit}`) }})
        </h2>
        <LineChart :labels="labels" :datasets="[{ label: t('trends.glucose'), data: measurementData('glucoseMeasurements') }]" />
      </div>
      <div class="mt-6 rounded border bg-white p-4">
        <h2 class="mb-2 font-medium">{{ t('trends.ketones') }}</h2>
        <LineChart :labels="labels" :datasets="[{ label: t('trends.ketones'), data: measurementData('ketoneMeasurements') }]" />
      </div>
    </template>
  </section>
</template>
