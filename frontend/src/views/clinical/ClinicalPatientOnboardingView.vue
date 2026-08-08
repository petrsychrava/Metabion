<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { useApiError } from '@/composables/useApiError'
import { formatDateTime } from '@/utils/dateTime'
import ClinicalOnboardingReviewPanel from '@/views/clinical/ClinicalOnboardingReviewPanel.vue'
import type { OnboardingSubmissionSummary } from '@/types/api'

const { t, locale } = useI18n()
const route = useRoute()
const { message, capture } = useApiError()

const patientProfileId = Number(route.params.patientProfileId)

const items = ref<OnboardingSubmissionSummary[]>([])
const selectedId = ref<number | null>(null)
const loading = ref(true)

const patientItems = computed(() => items.value.filter((item) => item.patientProfileId === patientProfileId))

async function load() {
  loading.value = true
  try {
    items.value = await clinicalApi.listOnboardingSubmissions()
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

async function onReviewed() {
  selectedId.value = null
  await load()
}

onMounted(load)
</script>

<template>
  <section class="mt-4">
    <h2 class="text-lg font-medium">{{ t('clinical.tabOnboarding') }}</h2>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else>
      <table class="mt-2 w-full border-collapse bg-white text-sm dark:bg-gray-800">
        <thead>
          <tr class="border-b text-left">
            <th class="p-2">{{ t('clinical.colSubmitted') }}</th>
            <th class="p-2">{{ t('clinical.colVersion') }}</th>
            <th class="p-2">{{ t('onboarding.diagnosisType') }}</th>
            <th class="p-2">{{ t('clinical.colStatus') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in patientItems" :key="item.id" data-testid="submission-row"
              class="cursor-pointer border-b hover:bg-gray-50 dark:hover:bg-gray-700"
              @click="selectedId = item.id">
            <td class="p-2">{{ formatDateTime(item.submittedAt, locale) }}</td>
            <td class="p-2">{{ item.version }}</td>
            <td class="p-2">{{ t(`onboarding.diagnosis.${item.diagnosisType}`) }}</td>
            <td class="p-2">{{ t(`onboarding.reviewStatus.${item.reviewStatus}`) }}</td>
          </tr>
        </tbody>
      </table>
      <ClinicalOnboardingReviewPanel v-if="selectedId !== null" :key="selectedId"
                                     :submission-id="selectedId" class="mt-4"
                                     @reviewed="onReviewed" />
    </template>
  </section>
</template>
