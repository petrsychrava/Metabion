<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { useApiError } from '@/composables/useApiError'
import { formatDateTime } from '@/utils/dateTime'
import type { AnswerResponse, ClinicalDailyCheckInDetail } from '@/types/api'

const { t, locale } = useI18n()
const route = useRoute()
const { message, capture, clear } = useApiError()

const patientProfileId = Number(route.params.patientProfileId)
const date = ref(route.params.date as string)

const detail = ref<ClinicalDailyCheckInDetail | null>(null)
const loading = ref(true)
let loadGeneration = 0

function answerValue(answer: AnswerResponse): string {
  const value = answer.optionLabel ?? answer.answerNumeric ?? answer.answerText
  return value === null || value === undefined ? t('clinical.noValue') : String(value)
}

async function load(nextDate: string) {
  const gen = ++loadGeneration
  clear()
  date.value = nextDate
  detail.value = null
  loading.value = true
  try {
    const result = await clinicalApi.getDailyCheckIn(patientProfileId, nextDate)
    if (gen !== loadGeneration) return
    detail.value = result
  } catch (e) {
    if (gen !== loadGeneration) return
    capture(e)
  } finally {
    if (gen === loadGeneration) loading.value = false
  }
}

onMounted(() => { void load(route.params.date as string) })
watch(() => route.params.date as string, (nextDate) => { void load(nextDate) })
</script>

<template>
  <section class="mt-4">
    <h2 class="text-lg font-medium">{{ t('clinical.dayDetailTitle') }} — {{ date }}</h2>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else-if="detail">
      <div class="mt-4 rounded border bg-white p-4 dark:bg-gray-800">
        <h3 class="font-medium">{{ t('clinical.colDietLog') }}</h3>
        <p v-if="!detail.dietLog" class="mt-2 text-sm text-gray-600 dark:text-gray-400">{{ t('clinical.noDietLog') }}</p>
        <template v-else>
          <p class="mt-2 text-sm">
            {{ t(`enums.DietAdherenceLevel.${detail.dietLog.adherenceLevel}`) }}
            · {{ t(`enums.AppetiteLevel.${detail.dietLog.appetiteLevel}`) }}
          </p>
          <p v-if="detail.dietLog.notes" class="mt-1 text-sm text-gray-600 dark:text-gray-400">{{ detail.dietLog.notes }}</p>

          <h4 class="mt-4 text-sm font-medium">{{ t('clinical.meals') }}</h4>
          <table class="mt-1 w-full border-collapse text-sm">
            <tbody>
              <tr v-for="meal in detail.dietLog.meals" :key="meal.id" class="border-b">
                <td class="p-2">{{ t(`enums.MealType.${meal.mealType}`) }}</td>
                <td class="p-2">{{ meal.foodDescription ?? t('clinical.noValue') }}</td>
                <td class="p-2 text-gray-600 dark:text-gray-400">{{ meal.notes ?? '' }}</td>
              </tr>
            </tbody>
          </table>

          <template v-if="detail.dietLog.deviations.length > 0">
            <h4 class="mt-4 text-sm font-medium">{{ t('clinical.deviations') }}</h4>
            <table class="mt-1 w-full border-collapse text-sm">
              <tbody>
                <tr v-for="deviation in detail.dietLog.deviations" :key="deviation.id" class="border-b">
                  <td class="p-2">{{ t(`enums.DietDeviationCategory.${deviation.deviationCategory}`) }}</td>
                  <td class="p-2">{{ t(`enums.DietDeviationSeverity.${deviation.severity}`) }}</td>
                  <td class="p-2 text-gray-600 dark:text-gray-400">{{ deviation.notes ?? '' }}</td>
                </tr>
              </tbody>
            </table>
          </template>

          <template v-if="detail.dietLog.measurements.length > 0">
            <h4 class="mt-4 text-sm font-medium">{{ t('clinical.measurements') }}</h4>
            <table class="mt-1 w-full border-collapse text-sm">
              <tbody>
                <tr v-for="m in detail.dietLog.measurements" :key="m.id" class="border-b">
                  <td class="p-2">{{ t(`enums.MeasurementType.${m.measurementType}`) }}</td>
                  <td class="p-2">{{ m.value }} {{ t(`enums.MeasurementUnit.${m.unit}`) }}</td>
                  <td class="p-2">{{ t(`enums.MeasurementContext.${m.context}`) }}</td>
                  <td class="p-2 text-gray-600 dark:text-gray-400">{{ formatDateTime(m.measuredAt, locale) }}</td>
                </tr>
              </tbody>
            </table>
          </template>

          <template v-if="detail.dietLog.photoReferences.length > 0">
            <h4 class="mt-4 text-sm font-medium">{{ t('clinical.photos') }}</h4>
            <div class="mt-2 flex flex-wrap gap-2">
              <a v-for="photo in detail.dietLog.photoReferences" :key="photo.id"
                 :href="photo.contentUrl" target="_blank" rel="noopener">
                <img :src="photo.contentUrl" :alt="photo.caption ?? photo.originalFilename"
                     class="h-24 w-24 rounded border object-cover dark:border-gray-600" />
              </a>
            </div>
          </template>
        </template>
      </div>

      <div class="mt-4 rounded border bg-white p-4 dark:bg-gray-800">
        <h3 class="font-medium">{{ t('clinical.colSymptomCheckIn') }}</h3>
        <p v-if="!detail.symptomCheckIn" class="mt-2 text-sm text-gray-600 dark:text-gray-400">{{ t('clinical.noSymptomCheckIn') }}</p>
        <template v-else>
          <p class="mt-2 text-sm">
            {{ t(`checkIn.FlareState.${detail.symptomCheckIn.flareState}`) }}
            · {{ t('clinical.colScore') }}: {{ detail.symptomCheckIn.totalSymptomScore ?? t('clinical.noValue') }}
          </p>
          <p v-if="detail.symptomCheckIn.notes" class="mt-1 text-sm text-gray-600 dark:text-gray-400">{{ detail.symptomCheckIn.notes }}</p>
          <h4 class="mt-4 text-sm font-medium">{{ t('clinical.answers') }}</h4>
          <table class="mt-1 w-full border-collapse text-sm">
            <tbody>
              <tr v-for="answer in detail.symptomCheckIn.answers" :key="answer.questionId" class="border-b">
                <td class="p-2">{{ answer.label }}</td>
                <td class="p-2">{{ answerValue(answer) }}</td>
              </tr>
            </tbody>
          </table>
        </template>
      </div>
    </template>
  </section>
</template>
