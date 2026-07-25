<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@/api/http'
import { onboardingApi } from '@/api/onboarding'
import { useApiError } from '@/composables/useApiError'
import FieldError from '@/components/FieldError.vue'
import type {
  AdvancedTherapyExposure,
  DiseaseActivityEstimate,
  IbdDiagnosisType,
  OnboardingSubmissionSummary,
  SteroidUse,
} from '@/types/api'

const { t } = useI18n()
const { message, fieldErrors, capture, clear } = useApiError()

const diagnosisTypes: IbdDiagnosisType[] = ['CROHNS_DISEASE', 'ULCERATIVE_COLITIS', 'IBD_UNCLASSIFIED']
const activityEstimates: DiseaseActivityEstimate[] = ['REMISSION', 'MILD', 'MODERATE', 'SEVERE', 'UNKNOWN']
const steroidUses: SteroidUse[] = ['NONE', 'CURRENT', 'RECENT_LAST_3_MONTHS']
const therapyExposures: AdvancedTherapyExposure[] = ['NEVER_USED', 'CURRENT', 'PAST', 'UNKNOWN']

const form = reactive({
  diagnosisType: 'CROHNS_DISEASE' as IbdDiagnosisType,
  diagnosisYear: null as number | null,
  diseaseLocation: '',
  diseaseBehavior: '',
  activityEstimate: 'UNKNOWN' as DiseaseActivityEstimate,
  currentMedications: '',
  steroidUse: 'NONE' as SteroidUse,
  advancedTherapyExposure: 'UNKNOWN' as AdvancedTherapyExposure,
  medicationNotes: '',
  labsCollectedAt: '',
  crpMgL: null as number | null,
  fecalCalprotectinUgG: null as number | null,
  hemoglobinGDl: null as number | null,
  albuminGDl: null as number | null,
  labNotes: '',
})

const history = ref<OnboardingSubmissionSummary[]>([])
const loading = ref(true)
const saved = ref(false)
const showForm = ref(false)

onMounted(async () => {
  try {
    history.value = await onboardingApi.history()
    showForm.value = history.value.length === 0
  } catch (e) {
    // No submissions yet (404) → show the form.
    if (e instanceof ApiError && e.status === 404) {
      showForm.value = true
    } else {
      capture(e)
    }
  } finally {
    loading.value = false
  }
})

async function submit() {
  clear()
  saved.value = false
  try {
    await onboardingApi.submit({
      diagnosisType: form.diagnosisType,
      diagnosisYear: form.diagnosisYear,
      diseaseLocation: form.diseaseLocation || undefined,
      diseaseBehavior: form.diseaseBehavior || undefined,
      activityEstimate: form.activityEstimate,
      currentMedications: form.currentMedications || undefined,
      steroidUse: form.steroidUse,
      advancedTherapyExposure: form.advancedTherapyExposure,
      medicationNotes: form.medicationNotes || undefined,
      labsCollectedAt: form.labsCollectedAt || null,
      crpMgL: form.crpMgL,
      fecalCalprotectinUgG: form.fecalCalprotectinUgG,
      hemoglobinGDl: form.hemoglobinGDl,
      albuminGDl: form.albuminGDl,
      labNotes: form.labNotes || undefined,
    })
    saved.value = true
    showForm.value = false
    history.value = await onboardingApi.history()
  } catch (e) {
    capture(e)
  }
}
</script>

<template>
  <section class="max-w-2xl">
    <h1 class="text-2xl font-semibold">{{ t('nav.onboarding') }}</h1>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else>
      <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
      <p v-if="saved" class="mt-4 rounded bg-green-50 p-3 text-sm text-green-700">{{ t('common.saved') }}</p>

      <div v-if="history.length > 0" class="mt-4">
        <h2 class="font-medium">{{ t('onboarding.history') }}</h2>
        <ul class="mt-2 space-y-2">
          <li v-for="s in history" :key="s.id" class="rounded border bg-white p-3 text-sm">
            v{{ s.version }} · {{ s.submittedAt.slice(0, 10) }} ·
            {{ t(`onboarding.diagnosis.${s.diagnosisType}`) }} ·
            {{ t(`onboarding.reviewStatus.${s.reviewStatus}`) }}
          </li>
        </ul>
        <button class="mt-3 rounded border px-3 py-1 text-sm" @click="showForm = !showForm">
          {{ showForm ? t('common.cancel') : t('onboarding.newSubmission') }}
        </button>
      </div>

      <form v-if="showForm" class="mt-4 space-y-4 rounded border bg-white p-4" @submit.prevent="submit">
        <div class="grid gap-4 sm:grid-cols-2">
          <div>
            <label class="block text-sm font-medium">{{ t('onboarding.diagnosisType') }}</label>
            <select v-model="form.diagnosisType" class="mt-1 w-full rounded border border-gray-300 px-3 py-2">
              <option v-for="d in diagnosisTypes" :key="d" :value="d">{{ t(`onboarding.diagnosis.${d}`) }}</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium">{{ t('onboarding.diagnosisYear') }}</label>
            <input v-model.number="form.diagnosisYear" type="number" min="1900" :max="new Date().getFullYear()"
                   class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
            <FieldError :message="fieldErrors.diagnosisYear" />
          </div>
          <div>
            <label class="block text-sm font-medium">{{ t('onboarding.diseaseLocation') }}</label>
            <input v-model="form.diseaseLocation" type="text" maxlength="120"
                   class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
          </div>
          <div>
            <label class="block text-sm font-medium">{{ t('onboarding.diseaseBehavior') }}</label>
            <input v-model="form.diseaseBehavior" type="text" maxlength="120"
                   class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
          </div>
          <div>
            <label class="block text-sm font-medium">{{ t('onboarding.activityEstimate') }}</label>
            <select v-model="form.activityEstimate" class="mt-1 w-full rounded border border-gray-300 px-3 py-2">
              <option v-for="a in activityEstimates" :key="a" :value="a">{{ t(`onboarding.activity.${a}`) }}</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium">{{ t('onboarding.steroidUse') }}</label>
            <select v-model="form.steroidUse" class="mt-1 w-full rounded border border-gray-300 px-3 py-2">
              <option v-for="s in steroidUses" :key="s" :value="s">{{ t(`onboarding.steroid.${s}`) }}</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium">{{ t('onboarding.advancedTherapy') }}</label>
            <select v-model="form.advancedTherapyExposure" class="mt-1 w-full rounded border border-gray-300 px-3 py-2">
              <option v-for="x in therapyExposures" :key="x" :value="x">{{ t(`onboarding.therapy.${x}`) }}</option>
            </select>
          </div>
        </div>
        <div>
          <label class="block text-sm font-medium">{{ t('onboarding.currentMedications') }}</label>
          <textarea v-model="form.currentMedications" rows="2" maxlength="1000"
                    class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        </div>
        <div>
          <label class="block text-sm font-medium">{{ t('onboarding.medicationNotes') }}</label>
          <textarea v-model="form.medicationNotes" rows="2" maxlength="1000"
                    class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        </div>

        <fieldset class="rounded border p-3">
          <legend class="px-1 text-sm font-medium">{{ t('onboarding.labs') }}</legend>
          <div class="grid gap-3 sm:grid-cols-2">
            <label class="text-sm">{{ t('onboarding.labsCollectedAt') }}
              <input v-model="form.labsCollectedAt" type="date" class="mt-1 w-full rounded border border-gray-300 px-2 py-1" />
            </label>
            <label class="text-sm">CRP (mg/L)
              <input v-model.number="form.crpMgL" type="number" step="any" min="0" max="500"
                     class="mt-1 w-full rounded border border-gray-300 px-2 py-1" />
            </label>
            <label class="text-sm">{{ t('onboarding.calprotectin') }} (µg/g)
              <input v-model.number="form.fecalCalprotectinUgG" type="number" step="any" min="0" max="10000"
                     class="mt-1 w-full rounded border border-gray-300 px-2 py-1" />
            </label>
            <label class="text-sm">{{ t('onboarding.hemoglobin') }} (g/dL)
              <input v-model.number="form.hemoglobinGDl" type="number" step="any" min="0" max="25"
                     class="mt-1 w-full rounded border border-gray-300 px-2 py-1" />
            </label>
            <label class="text-sm">{{ t('onboarding.albumin') }} (g/dL)
              <input v-model.number="form.albuminGDl" type="number" step="any" min="0" max="10"
                     class="mt-1 w-full rounded border border-gray-300 px-2 py-1" />
            </label>
          </div>
          <textarea v-model="form.labNotes" rows="2" maxlength="1000" :placeholder="t('labs.notes')"
                    class="mt-3 w-full rounded border border-gray-300 px-3 py-2" />
        </fieldset>

        <button type="submit" class="rounded bg-blue-600 px-6 py-2 text-white">{{ t('common.save') }}</button>
      </form>
    </template>
  </section>
</template>
