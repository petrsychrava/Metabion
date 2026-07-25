<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { labApi } from '@/api/labs'
import { useApiError } from '@/composables/useApiError'
import type { LabResultSetResponse } from '@/types/api'

const { t } = useI18n()
const { message, capture } = useApiError()

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const yearAgo = new Date(today)
yearAgo.setFullYear(yearAgo.getFullYear() - 1)

const from = ref(iso(yearAgo))
const to = ref(iso(today))
const sets = ref<LabResultSetResponse[]>([])
const loading = ref(true)
const removalTarget = ref<LabResultSetResponse | null>(null)
const removalReason = ref('')
const removalDone = ref(false)

async function load() {
  loading.value = true
  try {
    sets.value = await labApi.listResultSets(from.value, to.value)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function confirmRemoval() {
  const target = removalTarget.value
  if (!target) return
  try {
    await labApi.requestRemoval(target.id, target.version, removalReason.value)
    removalTarget.value = null
    removalReason.value = ''
    removalDone.value = true
    await load()
  } catch (e) {
    capture(e)
  }
}
</script>

<template>
  <section>
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-semibold">{{ t('labs.title') }}</h1>
      <div class="flex gap-3 text-sm">
        <router-link to="/labs/trends" class="text-blue-600">{{ t('labs.trendTitle') }}</router-link>
        <router-link to="/labs/new" class="rounded bg-blue-600 px-3 py-1 text-white">{{ t('labs.newResultSet') }}</router-link>
      </div>
    </div>

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
    <p v-if="removalDone" class="mt-4 rounded bg-green-50 p-3 text-sm text-green-700">{{ t('labs.removalRequested') }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <table v-else class="mt-4 w-full border-collapse bg-white text-sm">
      <thead>
        <tr class="border-b text-left">
          <th class="p-2">{{ t('labs.collectionDate') }}</th>
          <th class="p-2">{{ t('labs.results') }}</th>
          <th class="p-2">{{ t('labs.status') }}</th>
          <th class="p-2"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="set in sets" :key="set.id" class="border-b">
          <td class="p-2">{{ set.collectionDate }}</td>
          <td class="p-2">{{ set.results.map((r) => `${r.label}: ${r.reportedValue} ${r.reportedUnit}`).join(', ') }}</td>
          <td class="p-2">{{ set.confirmationStatus === 'CONFIRMED' ? t('labs.confirmed') : t('labs.unconfirmed') }}</td>
          <td class="p-2 text-right">
            <template v-if="set.createdByCurrentPatient">
              <router-link :to="`/labs/${set.id}`" class="mr-3 text-blue-600">{{ t('labs.edit') }}</router-link>
              <button class="text-red-600" @click="removalTarget = set; removalDone = false">{{ t('labs.requestRemoval') }}</button>
            </template>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="removalTarget" class="fixed inset-0 flex items-center justify-center bg-black/40">
      <div class="w-full max-w-sm rounded bg-white p-6">
        <h2 class="font-medium">{{ t('labs.requestRemoval') }}</h2>
        <label class="mt-3 block text-sm">{{ t('labs.removalReason') }}
          <input v-model="removalReason" type="text" maxlength="500"
                 class="mt-1 w-full rounded border border-gray-300 px-2 py-1" />
        </label>
        <div class="mt-4 flex justify-end gap-2">
          <button class="rounded border px-3 py-1 text-sm" @click="removalTarget = null">{{ t('common.cancel') }}</button>
          <button class="rounded bg-red-600 px-3 py-1 text-sm text-white" @click="confirmRemoval">{{ t('account.confirm') }}</button>
        </div>
      </div>
    </div>
  </section>
</template>
