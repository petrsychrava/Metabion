<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@/api/http'
import { accountApi } from '@/api/account'
import { dietLogApi } from '@/api/dietLogs'
import { useApiError } from '@/composables/useApiError'
import { formatForDateTimeInput, instantWithinDate, parseDateTimeInput } from '@/utils/patientTimezone'
import FieldError from '@/components/FieldError.vue'
import PhotoUpload from '@/components/PhotoUpload.vue'
import type {
  AppetiteLevel,
  DailyMeasurementEntryRequest,
  DeviationRequest,
  DietAdherenceLevel,
  DietDeviationCategory,
  DietDeviationSeverity,
  DietLogPhotoUploadResponse,
  MealRequest,
  MealType,
  MeasurementContext,
  MeasurementType,
  MeasurementUnit,
  PhotoUploadReferenceRequest,
} from '@/types/api'

const { t } = useI18n()
const route = useRoute()
const { message, fieldErrors, capture, clear } = useApiError()

const logDate = computed(() => route.params.date as string)

const adherenceLevel = ref<DietAdherenceLevel>('FULL')
const appetiteLevel = ref<AppetiteLevel>('NORMAL')
const notes = ref('')
// Carried through untouched: metadata is set by REST/MCP clients and would be
// wiped by the replacing save if the editor dropped it.
const logMetadata = ref<string | null>(null)
const meals = reactive<MealRequest[]>([])
const deviations = reactive<DeviationRequest[]>([])
const photoReferences = reactive<(PhotoUploadReferenceRequest & { contentUrl?: string })[]>([])
const measurements = reactive<DailyMeasurementEntryRequest[]>([])
const loading = ref(true)
const loadFailed = ref(false)
const saved = ref(false)
// Backend validates measuredAt against the log date's day in the patient
// timezone; null falls back to browser-local semantics.
const patientTimezone = ref<string | null>(null)

const adherenceOptions: DietAdherenceLevel[] = ['FULL', 'MOSTLY', 'PARTIAL', 'LOW', 'NOT_FOLLOWED']
const appetiteOptions: AppetiteLevel[] = ['LOW', 'NORMAL', 'HIGH', 'VARIABLE']
const mealTypeOptions: MealType[] = ['BREAKFAST', 'LUNCH', 'DINNER', 'SNACK', 'DRINK', 'OTHER']
const deviationCategoryOptions: DietDeviationCategory[] = ['EXCESS_CARBS', 'NON_PROTOCOL_FOOD', 'MISSED_MEAL', 'DINING_OUT', 'ALCOHOL', 'GI_TOLERANCE', 'OTHER']
const deviationSeverityOptions: DietDeviationSeverity[] = ['MINOR', 'MODERATE', 'MAJOR']
const measurementTypeOptions: MeasurementType[] = ['GLUCOSE', 'KETONE']
const measurementUnitOptions: MeasurementUnit[] = ['MMOL_L', 'MG_DL']
const measurementContextOptions: MeasurementContext[] = ['FASTING', 'PRE_MEAL', 'POST_MEAL', 'BEDTIME', 'SYMPTOMS', 'OTHER']

function unitOptionsFor(type: MeasurementType): MeasurementUnit[] {
  // The backend only accepts MMOL_L for ketone measurements.
  return type === 'KETONE' ? ['MMOL_L'] : measurementUnitOptions
}

function onMeasurementTypeChange(m: DailyMeasurementEntryRequest) {
  if (m.measurementType === 'KETONE') m.unit = 'MMOL_L'
}

// The control shows and edits wall time in the patient timezone so the visible
// date stays consistent with the log's patient-timezone day.
function toLocalInputValue(iso: string): string {
  return formatForDateTimeInput(iso, patientTimezone.value)
}

onMounted(async () => {
  accountApi.getProfile()
    .then((p) => { patientTimezone.value = p.timezone })
    .catch(() => { /* keep the browser-local fallback */ })
  try {
    const log = await dietLogApi.get(logDate.value)
    adherenceLevel.value = log.adherenceLevel
    appetiteLevel.value = log.appetiteLevel
    notes.value = log.notes ?? ''
    logMetadata.value = log.metadata
    meals.push(...log.meals.map((m) => ({ mealType: m.mealType, foodDescription: m.foodDescription ?? '', notes: m.notes ?? '' })))
    const mealIndexById = new Map(log.meals.map((m, idx) => [m.id, idx]))
    // Legacy rows may carry a null or dangling mealId; a null mealIndex would
    // fail the next save, so fall back to the first meal while meals exist.
    const toMealIndex = (mealId: number | null): number | null => {
      if (meals.length === 0) return null
      if (mealId == null) return 0
      return mealIndexById.get(mealId) ?? 0
    }
    deviations.push(...log.deviations.map((d) => ({
      mealIndex: toMealIndex(d.mealId),
      deviationCategory: d.deviationCategory,
      severity: d.severity,
      notes: d.notes ?? '',
    })))
    photoReferences.push(...log.photoReferences.map((p) => ({ mealIndex: toMealIndex(p.mealId), uploadId: p.id, caption: p.caption ?? '', contentUrl: p.contentUrl })))
    measurements.push(...log.measurements.map((m) => ({
      measurementType: m.measurementType,
      value: m.value,
      unit: m.unit,
      measuredAt: m.measuredAt,
      context: m.context,
      notes: m.notes ?? '',
      metadata: m.metadata ?? undefined,
    })))
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      // no entry for this date yet — start blank
    } else {
      // Load failed: show the error and withhold the editor so a blind save
      // cannot wipe the day's existing data (the save is a replacing upsert).
      capture(e)
      loadFailed.value = true
    }
  } finally {
    loading.value = false
  }
})

function addMeal() {
  meals.push({ mealType: 'BREAKFAST', foodDescription: '', notes: '' })
}

function removeMeal(i: number) {
  meals.splice(i, 1)
  for (let j = deviations.length - 1; j >= 0; j--) {
    const mi = deviations[j].mealIndex
    if (mi === i) deviations.splice(j, 1)
    else if (mi != null && mi > i) deviations[j].mealIndex = mi - 1
  }
  for (let j = photoReferences.length - 1; j >= 0; j--) {
    const mi = photoReferences[j].mealIndex
    if (mi === i) photoReferences.splice(j, 1)
    else if (mi != null && mi > i) photoReferences[j].mealIndex = mi - 1
  }
}

function addDeviation() {
  deviations.push({ mealIndex: meals.length - 1, deviationCategory: 'OTHER', severity: 'MINOR', notes: '' })
}

function addMeasurement() {
  // Default into the edited log's day — "now" only when editing today's log —
  // otherwise the replacing save is rejected by MeasurementValidator.
  measurements.push({ measurementType: 'GLUCOSE', value: 5.0, unit: 'MMOL_L', measuredAt: instantWithinDate(logDate.value, patientTimezone.value), context: 'FASTING', notes: '' })
}

function onMeasuredAtInput(m: DailyMeasurementEntryRequest, event: Event) {
  const value = (event.target as HTMLInputElement).value
  if (!value) return
  m.measuredAt = parseDateTimeInput(value, patientTimezone.value)
}

function onPhotoUploaded(photo: DietLogPhotoUploadResponse) {
  photoReferences.push({ mealIndex: meals.length - 1, uploadId: photo.uploadId, caption: '', contentUrl: photo.contentUrl })
}

async function save() {
  clear()
  saved.value = false
  try {
    await dietLogApi.save({
      logDate: logDate.value,
      adherenceLevel: adherenceLevel.value,
      appetiteLevel: appetiteLevel.value,
      notes: notes.value || undefined,
      metadata: logMetadata.value ?? undefined,
      meals,
      deviations,
      photoReferences: photoReferences.map(({ contentUrl: _contentUrl, ...rest }) => rest),
      measurements,
    })
    saved.value = true
  } catch (e) {
    capture(e)
  }
}
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('dietLog.title') }} — {{ logDate }}</h1>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <p v-else-if="loadFailed" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <div v-else class="mt-4 space-y-6">
      <p v-if="message" class="rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
      <p v-if="saved" class="rounded bg-green-50 p-3 text-sm text-green-700 dark:bg-green-950 dark:text-green-300">{{ t('common.saved') }}</p>

      <div class="grid gap-4 rounded border bg-white p-4 dark:bg-gray-800 sm:grid-cols-2">
        <div>
          <label class="block text-sm font-medium" for="adherence">{{ t('dietLog.adherence') }}</label>
          <select id="adherence" v-model="adherenceLevel" data-testid="adherence"
                  class="mt-1 w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-800">
            <option v-for="o in adherenceOptions" :key="o" :value="o">{{ t(`enums.DietAdherenceLevel.${o}`) }}</option>
          </select>
          <FieldError :message="fieldErrors.adherenceLevel" />
        </div>
        <div>
          <label class="block text-sm font-medium" for="appetite">{{ t('dietLog.appetite') }}</label>
          <select id="appetite" v-model="appetiteLevel" class="mt-1 w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-800">
            <option v-for="o in appetiteOptions" :key="o" :value="o">{{ t(`enums.AppetiteLevel.${o}`) }}</option>
          </select>
          <FieldError :message="fieldErrors.appetiteLevel" />
        </div>
        <div class="sm:col-span-2">
          <label class="block text-sm font-medium" for="notes">{{ t('dietLog.notes') }}</label>
          <textarea id="notes" v-model="notes" maxlength="1000" rows="2"
                    class="mt-1 w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-800" />
          <FieldError :message="fieldErrors.notes" />
        </div>
      </div>

      <div class="rounded border bg-white p-4 dark:bg-gray-800">
        <div class="flex items-center justify-between">
          <h2 class="font-medium">{{ t('dietLog.meals') }}</h2>
          <button data-testid="add-meal" class="rounded border px-3 py-1 text-sm" @click="addMeal">{{ t('dietLog.addMeal') }}</button>
        </div>
        <div v-for="(meal, i) in meals" :key="i" :data-testid="`meal-row-${i}`" class="mt-3 grid gap-2 border-t pt-3 sm:grid-cols-[10rem_1fr_auto]">
          <select v-model="meal.mealType" class="rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
            <option v-for="o in mealTypeOptions" :key="o" :value="o">{{ t(`enums.MealType.${o}`) }}</option>
          </select>
          <input v-model="meal.foodDescription" :data-testid="`meal-desc-${i}`" type="text" maxlength="500"
                 :placeholder="t('dietLog.foodDescription')" class="rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
          <button class="text-sm text-red-600 dark:text-red-400" @click="removeMeal(i)">{{ t('common.remove') }}</button>
        </div>
      </div>

      <div class="rounded border bg-white p-4 dark:bg-gray-800">
        <div class="flex items-center justify-between">
          <h2 class="font-medium">{{ t('dietLog.deviations') }}</h2>
          <button data-testid="add-deviation" class="rounded border px-3 py-1 text-sm" :disabled="meals.length === 0"
                  @click="addDeviation">{{ t('dietLog.addDeviation') }}</button>
        </div>
        <p v-if="meals.length === 0" class="mt-2 text-sm text-gray-500 dark:text-gray-400">{{ t('dietLog.addMealFirst') }}</p>
        <div v-for="(dev, i) in deviations" :key="i" class="mt-3 grid gap-2 border-t pt-3 sm:grid-cols-[10rem_1fr_8rem_1fr_auto]">
          <select v-model="dev.mealIndex" :data-testid="`dev-meal-${i}`" class="rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
            <option v-for="(meal, mi) in meals" :key="mi" :value="mi">#{{ mi + 1 }} {{ t(`enums.MealType.${meal.mealType}`) }}</option>
          </select>
          <select v-model="dev.deviationCategory" class="rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
            <option v-for="o in deviationCategoryOptions" :key="o" :value="o">{{ t(`enums.DietDeviationCategory.${o}`) }}</option>
          </select>
          <select v-model="dev.severity" class="rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
            <option v-for="o in deviationSeverityOptions" :key="o" :value="o">{{ t(`enums.DietDeviationSeverity.${o}`) }}</option>
          </select>
          <input v-model="dev.notes" type="text" maxlength="1000" :placeholder="t('dietLog.notes')"
                 class="rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
          <button class="text-sm text-red-600 dark:text-red-400" @click="deviations.splice(i, 1)">{{ t('common.remove') }}</button>
        </div>
      </div>

      <div class="rounded border bg-white p-4 dark:bg-gray-800">
        <div class="flex items-center justify-between">
          <h2 class="font-medium">{{ t('dietLog.measurements') }}</h2>
          <button data-testid="add-measurement" class="rounded border px-3 py-1 text-sm" @click="addMeasurement">{{ t('dietLog.addMeasurement') }}</button>
        </div>
        <div v-for="(m, i) in measurements" :key="i" class="mt-3 grid gap-2 border-t pt-3 sm:grid-cols-[8rem_6rem_7rem_1fr_auto]">
          <select v-model="m.measurementType" :data-testid="`measurement-type-${i}`" class="rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"
                  @change="onMeasurementTypeChange(m)">
            <option v-for="o in measurementTypeOptions" :key="o" :value="o">{{ t(`enums.MeasurementType.${o}`) }}</option>
          </select>
          <input v-model.number="m.value" type="number" step="0.1" min="0" class="rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
          <select v-model="m.unit" :data-testid="`measurement-unit-${i}`" class="rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
            <option v-for="o in unitOptionsFor(m.measurementType)" :key="o" :value="o">{{ t(`enums.MeasurementUnit.${o}`) }}</option>
          </select>
          <div class="flex gap-2">
            <select v-model="m.context" class="rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
              <option v-for="o in measurementContextOptions" :key="o" :value="o">{{ t(`enums.MeasurementContext.${o}`) }}</option>
            </select>
            <input :value="toLocalInputValue(m.measuredAt)" type="datetime-local"
                   class="rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"
                   @input="onMeasuredAtInput(m, $event)" />
          </div>
          <button class="text-sm text-red-600 dark:text-red-400" @click="measurements.splice(i, 1)">{{ t('common.remove') }}</button>
        </div>
      </div>

      <div class="rounded border bg-white p-4 dark:bg-gray-800">
        <h2 class="font-medium">{{ t('dietLog.photos') }}</h2>
        <PhotoUpload v-if="meals.length > 0" class="mt-2" @uploaded="onPhotoUploaded" />
        <p v-else class="mt-2 text-sm text-gray-500 dark:text-gray-400">{{ t('dietLog.addMealFirst') }}</p>
        <div class="mt-3 flex flex-wrap gap-3">
          <figure v-for="(p, i) in photoReferences" :key="p.uploadId" class="w-32">
            <img :src="p.contentUrl" :alt="p.caption ?? ''" class="h-24 w-32 rounded border object-cover" />
            <select v-model="p.mealIndex" :data-testid="`photo-meal-${i}`"
                    class="mt-1 w-full rounded border border-gray-300 px-2 py-1 text-xs dark:border-gray-600 dark:bg-gray-800">
              <option v-for="(meal, mi) in meals" :key="mi" :value="mi">#{{ mi + 1 }} {{ t(`enums.MealType.${meal.mealType}`) }}</option>
            </select>
            <input v-model="p.caption" type="text" maxlength="500" :placeholder="t('dietLog.photoCaption')"
                   class="mt-1 w-full rounded border border-gray-300 px-2 py-1 text-xs dark:border-gray-600 dark:bg-gray-800" />
            <button class="mt-1 text-xs text-red-600 dark:text-red-400" @click="photoReferences.splice(i, 1)">{{ t('common.remove') }}</button>
          </figure>
        </div>
      </div>

      <button data-testid="save" class="rounded bg-blue-600 px-6 py-2 text-white" @click="save">{{ t('common.save') }}</button>
    </div>
  </section>
</template>
