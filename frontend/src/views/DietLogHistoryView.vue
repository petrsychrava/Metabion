<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { dietLogApi } from '@/api/dietLogs'
import { useApiError } from '@/composables/useApiError'
import { dateRangeError } from '@/utils/dateRange'
import type { DailyDietLogSummary } from '@/types/api'

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
const logs = ref<DailyDietLogSummary[]>([])
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
    logs.value = await dietLogApi.list(from.value, to.value)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('dietLog.history') }}</h1>
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
    <table v-else class="mt-4 w-full border-collapse bg-white text-sm">
      <thead>
        <tr class="border-b text-left">
          <th class="p-2">{{ t('dashboard.dietLog') }}</th>
          <th class="p-2">{{ t('dietLog.adherence') }}</th>
          <th class="p-2">{{ t('dietLog.mealCount') }}</th>
          <th class="p-2">{{ t('dietLog.deviationCount') }}</th>
          <th class="p-2">{{ t('dietLog.measurementCount') }}</th>
          <th class="p-2"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="log in logs" :key="log.id" class="border-b">
          <td class="p-2">{{ log.logDate }}</td>
          <td class="p-2">{{ t(`enums.DietAdherenceLevel.${log.adherenceLevel}`) }}</td>
          <td class="p-2">{{ log.mealCount }}</td>
          <td class="p-2">{{ log.deviationCount }}</td>
          <td class="p-2">{{ log.measurementCount }}</td>
          <td class="p-2">
            <router-link :to="`/diet-logs/${log.logDate}`" class="text-blue-600">{{ t('dietLog.open') }}</router-link>
          </td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
