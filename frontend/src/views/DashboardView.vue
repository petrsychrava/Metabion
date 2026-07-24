<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@/api/http'
import { dietLogApi } from '@/api/dietLogs'
import { symptomApi } from '@/api/symptoms'

const { t } = useI18n()
const dietLogDone = ref(false)
const checkInDone = ref(false)
const loading = ref(true)

function todayIso(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function exists(fetcher: () => Promise<unknown>): Promise<boolean> {
  try {
    await fetcher()
    return true
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return false
    throw e
  }
}

onMounted(async () => {
  const today = todayIso()
  ;[dietLogDone.value, checkInDone.value] = await Promise.all([
    exists(() => dietLogApi.get(today)),
    exists(() => symptomApi.getCheckIn(today)),
  ])
  loading.value = false
})
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('dashboard.title') }}</h1>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <div v-else class="mt-4 grid gap-4 sm:grid-cols-2">
      <router-link :to="`/diet-logs/${todayIso()}`" class="rounded border bg-white p-4 hover:border-blue-400">
        <h2 class="font-medium">{{ t('dashboard.dietLog') }}</h2>
        <p data-testid="diet-log-status" class="mt-1 text-sm" :class="dietLogDone ? 'text-green-700' : 'text-amber-700'">
          {{ dietLogDone ? t('dashboard.dietLogDone') : t('dashboard.dietLogOpen') }}
        </p>
      </router-link>
      <router-link :to="`/check-ins/${todayIso()}`" class="rounded border bg-white p-4 hover:border-blue-400">
        <h2 class="font-medium">{{ t('dashboard.checkIn') }}</h2>
        <p data-testid="check-in-status" class="mt-1 text-sm" :class="checkInDone ? 'text-green-700' : 'text-amber-700'">
          {{ checkInDone ? t('dashboard.checkInDone') : t('dashboard.checkInOpen') }}
        </p>
      </router-link>
    </div>
  </section>
</template>
