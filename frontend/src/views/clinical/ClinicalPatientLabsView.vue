<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { labApi } from '@/api/labs'
import { useApiError } from '@/composables/useApiError'
import { dateRangeError } from '@/utils/dateRange'
import LineChart from '@/components/LineChart.vue'
import type { LabResultSetResponse, LabTestDefinition, LabTrendResponse } from '@/types/api'

const { t } = useI18n()
const route = useRoute()
const { message, capture, clear } = useApiError()

const patientProfileId = Number(route.params.patientProfileId)

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const yearAgo = new Date(today)
yearAgo.setFullYear(yearAgo.getFullYear() - 1) // 12-month default, like the Thymeleaf page

const from = ref(iso(yearAgo))
const to = ref(iso(today))
const resultSets = ref<LabResultSetResponse[]>([])
const tests = ref<LabTestDefinition[]>([])
const selectedTest = ref('')
const trend = ref<LabTrendResponse | null>(null)
const loading = ref(true)

function rangeInvalid(): boolean {
  const rangeError = dateRangeError(from.value, to.value)
  if (rangeError) {
    message.value = t(`errors.date_range_${rangeError === 'too_long' ? 'too_long' : 'invalid'}`)
    return true
  }
  return false
}

async function loadList() {
  clear()
  if (rangeInvalid()) return
  loading.value = true
  try {
    resultSets.value = await clinicalApi.listLabResultSets(patientProfileId, from.value, to.value)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

async function loadTrend() {
  if (!selectedTest.value || rangeInvalid()) {
    trend.value = null
    return
  }
  try {
    trend.value = await clinicalApi.labTrend(patientProfileId, selectedTest.value, from.value, to.value)
  } catch (e) {
    capture(e)
  }
}

async function apply() {
  await loadList()
  await loadTrend()
}

onMounted(async () => {
  try {
    tests.value = await labApi.listTests()
  } catch (e) {
    capture(e)
  }
  await loadList()
})

watch(selectedTest, loadTrend)
</script>

<template>
  <section class="mt-4">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <h2 class="text-lg font-medium">{{ t('labs.title') }}</h2>
      <router-link :to="{ path: `/clinical/patients/${patientProfileId}/labs/new`, query: route.query }"
                   class="rounded bg-blue-600 px-3 py-1 text-sm text-white">
        {{ t('labs.newResultSet') }}
      </router-link>
    </div>

    <div class="mt-2 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('common.from') }}
        <input v-model="from" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <label class="text-sm">{{ t('common.to') }}
        <input v-model="to" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <button data-testid="apply-range" class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="apply">{{ t('common.apply') }}</button>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <table v-else data-testid="resultsets-table" class="mt-4 w-full border-collapse bg-white text-sm dark:bg-gray-800">
      <thead>
        <tr class="border-b text-left">
          <th class="p-2">{{ t('labs.collectionDate') }}</th>
          <th class="p-2">{{ t('labs.results') }}</th>
          <th class="p-2">{{ t('labs.status') }}</th>
          <th class="p-2"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="rs in resultSets" :key="rs.id" class="border-b">
          <td class="p-2">{{ rs.collectionDate }}</td>
          <td class="p-2">{{ rs.results.map((r) => r.testCode).join(', ') }}</td>
          <td class="p-2">{{ t(rs.confirmationStatus === 'CONFIRMED' ? 'labs.confirmed' : 'labs.unconfirmed') }}</td>
          <td class="p-2">
            <router-link :to="{ path: `/clinical/patients/${patientProfileId}/labs/${rs.id}`, query: route.query }"
                         class="text-blue-600 dark:text-blue-400">
              {{ t('labs.edit') }}
            </router-link>
          </td>
        </tr>
      </tbody>
    </table>

    <div class="mt-6 rounded border bg-white p-4 dark:bg-gray-800">
      <label class="text-sm">{{ t('labs.selectTest') }}
        <select v-model="selectedTest" data-testid="test-select"
                class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
          <option value="">—</option>
          <option v-for="test in tests" :key="test.code" :value="test.code">{{ test.label }}</option>
        </select>
      </label>
      <template v-if="trend">
        <h3 class="mb-2 mt-4 font-medium">{{ trend.label }} ({{ trend.canonicalUnit }})</h3>
        <LineChart
          :labels="trend.points.map((p) => p.collectionDate)"
          :datasets="[{ label: trend.label, data: trend.points.map((p) => p.canonicalValue) }]" />
      </template>
    </div>
  </section>
</template>
