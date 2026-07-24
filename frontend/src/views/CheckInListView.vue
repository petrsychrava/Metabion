<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { symptomApi } from '@/api/symptoms'
import { useApiError } from '@/composables/useApiError'
import type { SymptomCheckInResponse } from '@/types/api'

const { t } = useI18n()
const { message, capture } = useApiError()

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const monthAgo = new Date(today)
monthAgo.setDate(monthAgo.getDate() - 30)

const from = ref(iso(monthAgo))
const to = ref(iso(today))
const checkIns = ref<SymptomCheckInResponse[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    checkIns.value = await symptomApi.listCheckIns(from.value, to.value)
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
    <h1 class="text-2xl font-semibold">{{ t('checkIn.history') }}</h1>
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
          <th class="p-2">{{ t('checkIn.title') }}</th>
          <th class="p-2">{{ t('checkIn.flareState') }}</th>
          <th class="p-2">{{ t('checkIn.score') }}</th>
          <th class="p-2"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="c in checkIns" :key="c.id" class="border-b">
          <td class="p-2">{{ c.checkInDate }}</td>
          <td class="p-2">{{ t(`checkIn.FlareState.${c.flareState}`) }}</td>
          <td class="p-2">{{ c.totalSymptomScore ?? '—' }}</td>
          <td class="p-2">
            <router-link :to="`/check-ins/${c.checkInDate}`" class="text-blue-600">{{ t('dietLog.open') }}</router-link>
          </td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
