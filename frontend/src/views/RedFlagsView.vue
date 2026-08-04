<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { redFlagApi } from '@/api/redFlags'
import { useRedFlagsStore } from '@/stores/redFlags'
import { useApiError } from '@/composables/useApiError'
import { dateRangeError } from '@/utils/dateRange'
import { formatDateTime } from '@/utils/dateTime'
import { severityBadgeClass } from '@/utils/redFlags'
import type { PatientRedFlagEvent, RedFlagSeverity } from '@/types/api'

const { t, te, locale } = useI18n()
const { message, capture, clear } = useApiError()
const redFlags = useRedFlagsStore()

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const monthAgo = new Date(today)
monthAgo.setDate(monthAgo.getDate() - 30)

const from = ref(iso(monthAgo))
const to = ref(iso(today))
const severity = ref<RedFlagSeverity | ''>('')
const appliedFrom = ref(from.value)
const appliedTo = ref(to.value)
const appliedSeverity = ref<RedFlagSeverity | ''>(severity.value)
const items = ref<PatientRedFlagEvent[]>([])
const nextCursor = ref<string | null>(null)
const loading = ref(true)
const loadingMore = ref(false)

const severityOptions: RedFlagSeverity[] = ['ROUTINE_REVIEW', 'URGENT_REVIEW', 'EMERGENCY']

function ruleLabel(ruleKey: string): string {
  const key = `redFlags.rules.${ruleKey}`
  return te(key) ? t(key) : ruleKey
}

async function load() {
  clear()
  const rangeError = dateRangeError(from.value, to.value, 369)
  if (rangeError) {
    message.value = t(`errors.date_range_${rangeError === 'too_long' ? 'too_long' : 'invalid'}`)
    return
  }
  loading.value = true
  items.value = []
  nextCursor.value = null
  try {
    const page = await redFlagApi.getHistory({
      from: from.value,
      to: to.value,
      severity: severity.value || undefined,
      size: 25,
    })
    items.value = page.items
    nextCursor.value = page.nextCursor
    appliedFrom.value = from.value
    appliedTo.value = to.value
    appliedSeverity.value = severity.value
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (!nextCursor.value) return
  clear()
  loadingMore.value = true
  try {
    const page = await redFlagApi.getHistory({
      from: appliedFrom.value,
      to: appliedTo.value,
      severity: appliedSeverity.value || undefined,
      cursor: nextCursor.value,
      size: 25,
    })
    items.value = [...items.value, ...page.items]
    nextCursor.value = page.nextCursor
  } catch (e) {
    capture(e)
  } finally {
    loadingMore.value = false
  }
}

onMounted(() => {
  void redFlags.refreshCurrent()
  void load()
})
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('redFlags.title') }}</h1>

    <h2 class="mt-6 text-lg font-medium">{{ t('redFlags.currentTitle') }}</h2>
    <p v-if="redFlags.loadFailed" class="mt-2 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">
      {{ t('redFlags.currentLoadFailed') }}
    </p>
    <p v-else-if="!redFlags.snapshot" class="mt-2">{{ t('common.loading') }}</p>
    <p v-else-if="redFlags.snapshot.flags.length === 0" class="mt-2 text-sm">
      {{ t('redFlags.noCurrent') }}
    </p>
    <table v-else data-testid="current-table" class="mt-2 w-full border-collapse bg-white text-sm dark:bg-gray-800">
      <thead>
        <tr class="border-b text-left">
          <th class="p-2">{{ t('redFlags.rule') }}</th>
          <th class="p-2">{{ t('redFlags.severityHeader') }}</th>
          <th class="p-2">{{ t('redFlags.detected') }}</th>
          <th class="p-2">{{ t('redFlags.source') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="f in redFlags.snapshot.flags" :key="f.eventId" class="border-b">
          <td class="p-2">{{ ruleLabel(f.ruleKey) }}</td>
          <td class="p-2">
            <span class="rounded px-2 py-0.5" :class="severityBadgeClass(f.severity)">
              {{ t(`redFlags.severity.${f.severity}`) }}
            </span>
          </td>
          <td class="p-2">{{ formatDateTime(f.detectedAt, locale) }}</td>
          <td class="p-2">{{ t(`redFlags.sourceType.${f.sourceType}`) }}</td>
        </tr>
      </tbody>
    </table>

    <h2 class="mt-6 text-lg font-medium">{{ t('redFlags.historyTitle') }}</h2>
    <div class="mt-2 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('common.from') }}
        <input v-model="from" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <label class="text-sm">{{ t('common.to') }}
        <input v-model="to" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <label class="text-sm">{{ t('redFlags.severityHeader') }}
        <select v-model="severity" data-testid="severity-filter"
                class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
          <option value="">{{ t('redFlags.severityAll') }}</option>
          <option v-for="s in severityOptions" :key="s" :value="s">{{ t(`redFlags.severity.${s}`) }}</option>
        </select>
      </label>
      <button data-testid="apply-history" class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="load">
        {{ t('common.apply') }}
      </button>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else>
      <table data-testid="history-table" class="mt-4 w-full border-collapse bg-white text-sm dark:bg-gray-800">
        <thead>
          <tr class="border-b text-left">
            <th class="p-2">{{ t('redFlags.rule') }}</th>
            <th class="p-2">{{ t('redFlags.severityHeader') }}</th>
            <th class="p-2">{{ t('redFlags.detected') }}</th>
            <th class="p-2">{{ t('redFlags.source') }}</th>
            <th class="p-2">{{ t('redFlags.status') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="f in items" :key="f.eventId" class="border-b">
            <td class="p-2">{{ ruleLabel(f.ruleKey) }}</td>
            <td class="p-2">
              <span class="rounded px-2 py-0.5" :class="severityBadgeClass(f.severity)">
                {{ t(`redFlags.severity.${f.severity}`) }}
              </span>
            </td>
            <td class="p-2">{{ formatDateTime(f.detectedAt, locale) }}</td>
            <td class="p-2">{{ t(`redFlags.sourceType.${f.sourceType}`) }}</td>
            <td class="p-2">{{ f.current ? t('redFlags.statusCurrent') : t('redFlags.statusSuperseded') }}</td>
          </tr>
        </tbody>
      </table>
      <button v-if="nextCursor" data-testid="load-more" :disabled="loadingMore"
              class="mt-3 rounded border px-3 py-1 text-sm" @click="loadMore">
        {{ t('redFlags.loadMore') }}
      </button>
    </template>
  </section>
</template>
