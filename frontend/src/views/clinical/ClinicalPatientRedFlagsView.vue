<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { useApiError } from '@/composables/useApiError'
import { dateRangeError } from '@/utils/dateRange'
import { formatDateTime } from '@/utils/dateTime'
import { severityBadgeClass } from '@/utils/redFlags'
import type { ClinicalRedFlagEvent, ClinicalRedFlagSnapshot, RedFlagSeverity } from '@/types/api'

const { t, te, locale } = useI18n()
const route = useRoute()
const { message, capture, clear } = useApiError()

const patientProfileId = Number(route.params.patientProfileId)

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const monthAgo = new Date(today)
monthAgo.setDate(monthAgo.getDate() - 30)

const snapshot = ref<ClinicalRedFlagSnapshot | null>(null)
const snapshotFailed = ref(false)
const from = ref(iso(monthAgo))
const to = ref(iso(today))
const severity = ref<RedFlagSeverity | ''>('')
const appliedFrom = ref(from.value)
const appliedTo = ref(to.value)
const appliedSeverity = ref<RedFlagSeverity | ''>(severity.value)
const items = ref<ClinicalRedFlagEvent[]>([])
const nextCursor = ref<string | null>(null)
const loading = ref(true)
const loadingMore = ref(false)

const severityOptions: RedFlagSeverity[] = ['ROUTINE_REVIEW', 'URGENT_REVIEW', 'EMERGENCY']

function ruleLabel(ruleKey: string): string {
  const key = `redFlags.rules.${ruleKey}`
  return te(key) ? t(key) : ruleKey
}

let historyGeneration = 0

async function loadSnapshot() {
  try {
    snapshot.value = await clinicalApi.currentRedFlags(patientProfileId)
  } catch {
    snapshotFailed.value = true
  }
}

async function load() {
  clear()
  const rangeError = dateRangeError(from.value, to.value, 369)
  if (rangeError) {
    message.value = t(`errors.date_range_${rangeError === 'too_long' ? 'too_long' : 'invalid'}`)
    return
  }
  // Capture before the await: controls edited mid-flight must not leak into
  // the cursor bookkeeping, and a stale response must not overwrite a newer one.
  const requestFrom = from.value
  const requestTo = to.value
  const requestSeverity = severity.value
  const gen = ++historyGeneration
  loading.value = true
  loadingMore.value = false
  items.value = []
  nextCursor.value = null
  try {
    const page = await clinicalApi.redFlagHistory(patientProfileId, {
      from: requestFrom,
      to: requestTo,
      severity: requestSeverity || undefined,
      size: 25,
    })
    if (gen !== historyGeneration) return
    items.value = page.items
    nextCursor.value = page.nextCursor
    appliedFrom.value = requestFrom
    appliedTo.value = requestTo
    appliedSeverity.value = requestSeverity
  } catch (e) {
    if (gen !== historyGeneration) return
    capture(e)
  } finally {
    if (gen === historyGeneration) loading.value = false
  }
}

async function loadMore() {
  if (!nextCursor.value) return
  clear()
  const gen = historyGeneration
  loadingMore.value = true
  try {
    const page = await clinicalApi.redFlagHistory(patientProfileId, {
      from: appliedFrom.value,
      to: appliedTo.value,
      severity: appliedSeverity.value || undefined,
      cursor: nextCursor.value,
      size: 25,
    })
    if (gen !== historyGeneration) return
    items.value = [...items.value, ...page.items]
    nextCursor.value = page.nextCursor
  } catch (e) {
    if (gen !== historyGeneration) return
    capture(e)
  } finally {
    if (gen === historyGeneration) loadingMore.value = false
  }
}

onMounted(() => {
  void loadSnapshot()
  void load()
})
</script>

<template>
  <section class="mt-4">
    <h2 class="text-lg font-medium">{{ t('redFlags.currentTitle') }}</h2>
    <p v-if="snapshotFailed" class="mt-2 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">
      {{ t('redFlags.currentLoadFailed') }}
    </p>
    <p v-else-if="!snapshot" class="mt-2">{{ t('common.loading') }}</p>
    <p v-else-if="snapshot.flags.length === 0" class="mt-2 text-sm">{{ t('redFlags.noCurrent') }}</p>
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
        <tr v-for="f in snapshot.flags" :key="f.eventId" class="border-b">
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
        <select v-model="severity" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
          <option value="">{{ t('redFlags.severityAll') }}</option>
          <option v-for="s in severityOptions" :key="s" :value="s">{{ t(`redFlags.severity.${s}`) }}</option>
        </select>
      </label>
      <button class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="load">{{ t('common.apply') }}</button>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else>
      <table class="mt-4 w-full border-collapse bg-white text-sm dark:bg-gray-800">
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
          <tr v-for="f in items" :key="f.eventId" data-testid="history-row" class="border-b">
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
