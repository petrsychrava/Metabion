<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { labApi } from '@/api/labs'
import { useApiError } from '@/composables/useApiError'
import LineChart from '@/components/LineChart.vue'
import type { LabTestDefinition, LabTrendResponse } from '@/types/api'

const { t } = useI18n()
const { message, capture } = useApiError()

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const yearAgo = new Date(today)
yearAgo.setFullYear(yearAgo.getFullYear() - 1)

const tests = ref<LabTestDefinition[]>([])
const selectedTest = ref('')
const from = ref(iso(yearAgo))
const to = ref(iso(today))
const labels = ref<string[]>([])
const values = ref<(number | null)[]>([])
const trend = ref<LabTrendResponse | null>(null)
const loading = ref(false)

onMounted(async () => {
  try {
    tests.value = await labApi.listTests()
  } catch (e) {
    capture(e)
  }
})

async function load() {
  if (!selectedTest.value) return
  loading.value = true
  try {
    trend.value = await labApi.trend(selectedTest.value, from.value, to.value)
    labels.value = trend.value.points.map((p) => p.collectionDate)
    values.value = trend.value.points.map((p) => p.canonicalValue)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('labs.trendTitle') }}</h1>
    <div class="mt-4 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('labs.selectTest') }}
        <select v-model="selectedTest" class="ml-1 rounded border border-gray-300 px-2 py-1">
          <option value="" disabled>—</option>
          <option v-for="def in tests" :key="def.code" :value="def.code">{{ def.label }}</option>
        </select>
      </label>
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
    <div v-else-if="trend" class="mt-6 rounded border bg-white p-4">
      <h2 class="mb-2 font-medium">{{ trend.label }} ({{ trend.canonicalUnit }})</h2>
      <LineChart :labels="labels" :datasets="[{ label: trend.label, data: values }]" />
    </div>
  </section>
</template>
