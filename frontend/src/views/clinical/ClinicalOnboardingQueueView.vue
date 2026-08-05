<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { useApiError } from '@/composables/useApiError'
import { formatDateTime } from '@/utils/dateTime'
import type { OnboardingReviewStatus, OnboardingSubmissionSummary } from '@/types/api'

const { t, locale } = useI18n()
const router = useRouter()
const { message, capture, clear } = useApiError()

const status = ref<OnboardingReviewStatus | ''>('')
const items = ref<OnboardingSubmissionSummary[]>([])
const loading = ref(true)

const statusOptions: OnboardingReviewStatus[] = ['PENDING_REVIEW', 'REVIEWED', 'NEEDS_FOLLOW_UP']

async function load() {
  clear()
  loading.value = true
  try {
    items.value = await clinicalApi.listOnboardingSubmissions(undefined, status.value || undefined)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

function open(item: OnboardingSubmissionSummary) {
  void router.push(`/clinical/onboarding/${item.id}`)
}

onMounted(load)
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('clinical.queueTitle') }}</h1>
    <div class="mt-2 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('clinical.colStatus') }}
        <select v-model="status" data-testid="status-filter"
                class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
          <option value="">{{ t('clinical.allStatuses') }}</option>
          <option v-for="s in statusOptions" :key="s" :value="s">{{ t(`onboarding.reviewStatus.${s}`) }}</option>
        </select>
      </label>
      <button data-testid="apply-filter" class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="load">
        {{ t('common.apply') }}
      </button>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <p v-else-if="items.length === 0" class="mt-4 text-sm text-gray-600 dark:text-gray-400">{{ t('clinical.queueEmpty') }}</p>
    <table v-else class="mt-4 w-full border-collapse bg-white text-sm dark:bg-gray-800">
      <thead>
        <tr class="border-b text-left">
          <th class="p-2">{{ t('clinical.colPatient') }}</th>
          <th class="p-2">{{ t('clinical.colSubmitted') }}</th>
          <th class="p-2">{{ t('clinical.colVersion') }}</th>
          <th class="p-2">{{ t('onboarding.diagnosisType') }}</th>
          <th class="p-2">{{ t('clinical.colStatus') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.id" data-testid="queue-row"
            class="cursor-pointer border-b hover:bg-gray-50 dark:hover:bg-gray-700"
            @click="open(item)">
          <td class="p-2">{{ item.patientEmail }}</td>
          <td class="p-2">{{ formatDateTime(item.submittedAt, locale) }}</td>
          <td class="p-2">{{ item.version }}</td>
          <td class="p-2">{{ t(`onboarding.diagnosis.${item.diagnosisType}`) }}</td>
          <td class="p-2">{{ t(`onboarding.reviewStatus.${item.reviewStatus}`) }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
