<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@/api/http'
import { accountApi } from '@/api/account'
import { dietLogApi } from '@/api/dietLogs'
import { symptomApi } from '@/api/symptoms'
import { useApiError } from '@/composables/useApiError'
import { todayInTimezone } from '@/utils/patientTimezone'

const { t } = useI18n()
const { message, capture } = useApiError()
const dietLogDone = ref(false)
const checkInDone = ref(false)
const loading = ref(true)
// Browser-local guess until the profile timezone loads; the backend validates
// log/check-in dates against the patient timezone, not the browser's.
const today = ref(todayInTimezone(null))

async function exists(fetcher: () => Promise<unknown>): Promise<boolean> {
  try {
    await fetcher()
    return true
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return false
    // Non-404 failure: report it and treat the item as not done instead of hanging.
    capture(e)
    return false
  }
}

onMounted(async () => {
  try {
    today.value = todayInTimezone((await accountApi.getProfile()).timezone)
  } catch {
    // Profile unavailable — keep the browser-local date as a best effort.
  }
  const day = today.value
  ;[dietLogDone.value, checkInDone.value] = await Promise.all([
    exists(() => dietLogApi.get(day)),
    exists(() => symptomApi.getCheckIn(day)),
  ])
  loading.value = false
})
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('dashboard.title') }}</h1>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <div v-else class="mt-4 grid gap-4 sm:grid-cols-2">
      <router-link :to="`/diet-logs/${today}`" class="rounded border bg-white p-4 hover:border-blue-400 dark:bg-gray-800">
        <h2 class="font-medium">{{ t('dashboard.dietLog') }}</h2>
        <p data-testid="diet-log-status" class="mt-1 text-sm" :class="dietLogDone ? 'text-green-700 dark:text-green-300' : 'text-amber-700 dark:text-amber-300'">
          {{ dietLogDone ? t('dashboard.dietLogDone') : t('dashboard.dietLogOpen') }}
        </p>
      </router-link>
      <router-link :to="`/check-ins/${today}`" class="rounded border bg-white p-4 hover:border-blue-400 dark:bg-gray-800">
        <h2 class="font-medium">{{ t('dashboard.checkIn') }}</h2>
        <p data-testid="check-in-status" class="mt-1 text-sm" :class="checkInDone ? 'text-green-700 dark:text-green-300' : 'text-amber-700 dark:text-amber-300'">
          {{ checkInDone ? t('dashboard.checkInDone') : t('dashboard.checkInOpen') }}
        </p>
      </router-link>
    </div>
  </section>
</template>
