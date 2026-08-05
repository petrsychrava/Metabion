<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { useApiError } from '@/composables/useApiError'
import { formatDateTime } from '@/utils/dateTime'
import type { OnboardingSubmissionResponse } from '@/types/api'

const props = defineProps<{ submissionId: number }>()
const emit = defineEmits<(e: 'reviewed') => void>()

const { t, locale } = useI18n()
const { message, capture, clear } = useApiError()

const submission = ref<OnboardingSubmissionResponse | null>(null)
const loading = ref(true)
const decision = ref<'REVIEWED' | 'NEEDS_FOLLOW_UP'>('REVIEWED')
const reviewNotes = ref('')
const submitting = ref(false)

function val(v: string | number | null | undefined): string {
  return v === null || v === undefined || v === '' ? t('onboarding.notProvided') : String(v)
}

async function load() {
  clear()
  loading.value = true
  try {
    submission.value = await clinicalApi.getOnboardingSubmission(props.submissionId)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

async function submitReview() {
  clear()
  submitting.value = true
  try {
    submission.value = await clinicalApi.reviewOnboardingSubmission(props.submissionId, {
      reviewStatus: decision.value,
      reviewNotes: reviewNotes.value || undefined,
    })
    emit('reviewed')
  } catch (e) {
    capture(e)
  } finally {
    submitting.value = false
  }
}

onMounted(load)
watch(() => props.submissionId, load)
</script>

<template>
  <section class="rounded border bg-white p-4 dark:bg-gray-800">
    <p v-if="message" class="rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading">{{ t('common.loading') }}</p>
    <template v-else-if="submission">
      <dl class="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
        <div><dt class="inline font-medium">{{ t('clinical.colPatient') }}: </dt><dd class="inline">{{ submission.patientEmail }}</dd></div>
        <div><dt class="inline font-medium">{{ t('clinical.colSubmitted') }}: </dt><dd class="inline">{{ formatDateTime(submission.submittedAt, locale) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('account.dateOfBirth') }}: </dt><dd class="inline">{{ val(submission.dateOfBirth) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('account.sex') }}: </dt><dd class="inline">{{ submission.sex ? t(`sex.${submission.sex}`) : t('onboarding.notProvided') }}</dd></div>
        <div><dt class="inline font-medium">{{ t('account.countryRegion') }}: </dt><dd class="inline">{{ val(submission.countryRegion) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('account.timezone') }}: </dt><dd class="inline">{{ val(submission.timezone) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.diagnosisType') }}: </dt><dd class="inline">{{ t(`onboarding.diagnosis.${submission.diagnosisType}`) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.diagnosisYear') }}: </dt><dd class="inline">{{ val(submission.diagnosisYear) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.diseaseLocation') }}: </dt><dd class="inline">{{ val(submission.diseaseLocation) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.diseaseBehavior') }}: </dt><dd class="inline">{{ val(submission.diseaseBehavior) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.activityEstimate') }}: </dt><dd class="inline">{{ t(`onboarding.activity.${submission.activityEstimate}`) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.currentMedications') }}: </dt><dd class="inline">{{ val(submission.currentMedications) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.steroidUse') }}: </dt><dd class="inline">{{ t(`onboarding.steroid.${submission.steroidUse}`) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.advancedTherapy') }}: </dt><dd class="inline">{{ t(`onboarding.therapy.${submission.advancedTherapyExposure}`) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.medicationNotes') }}: </dt><dd class="inline">{{ val(submission.medicationNotes) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.labsCollectedAt') }}: </dt><dd class="inline">{{ val(submission.labsCollectedAt) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('clinical.crpMgL') }}: </dt><dd class="inline">{{ val(submission.crpMgL) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.calprotectin') }}: </dt><dd class="inline">{{ val(submission.fecalCalprotectinUgG) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.hemoglobin') }}: </dt><dd class="inline">{{ val(submission.hemoglobinGDl) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.albumin') }}: </dt><dd class="inline">{{ val(submission.albuminGDl) }}</dd></div>
      </dl>

      <p class="mt-4 text-sm">
        {{ t('clinical.colStatus') }}:
        <strong>{{ t(`onboarding.reviewStatus.${submission.reviewStatus}`) }}</strong>
      </p>
      <template v-if="submission.reviewStatus === 'PENDING_REVIEW'">
        <label class="mt-3 block text-sm font-medium">{{ t('clinical.reviewDecision') }}</label>
        <select v-model="decision" data-testid="review-decision"
                class="mt-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
          <option value="REVIEWED">{{ t('onboarding.reviewStatus.REVIEWED') }}</option>
          <option value="NEEDS_FOLLOW_UP">{{ t('onboarding.reviewStatus.NEEDS_FOLLOW_UP') }}</option>
        </select>
        <label class="mt-3 block text-sm font-medium">{{ t('clinical.reviewNotes') }}</label>
        <textarea v-model="reviewNotes" data-testid="review-notes" rows="3"
                  class="mt-1 w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-800" />
        <button data-testid="submit-review" :disabled="submitting"
                class="mt-3 rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50"
                @click="submitReview">
          {{ t('clinical.submitReview') }}
        </button>
      </template>
      <p v-else class="mt-3 text-sm text-gray-600 dark:text-gray-400">{{ t('clinical.alreadyReviewed') }}</p>
    </template>
  </section>
</template>
