<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@/api/http'
import { symptomApi } from '@/api/symptoms'
import { useApiError } from '@/composables/useApiError'
import { useRedFlagsStore } from '@/stores/redFlags'
import type { AnswerRequest, FlareState, SymptomCheckInResponse, SymptomQuestionnaire } from '@/types/api'

const { t } = useI18n()
const route = useRoute()
const { message, capture, clear } = useApiError()

const checkInDate = computed(() => route.params.date as string)
const flareOptions: FlareState[] = ['NO_FLARE', 'SUSPECTED_FLARE', 'ACTIVE_FLARE']

const questionnaire = ref<SymptomQuestionnaire | null>(null)
// Set when the stored check-in belongs to a retired questionnaire version:
// the backend rejects edits, so only a read-only summary is shown.
const retiredCheckIn = ref<SymptomCheckInResponse | null>(null)
const flareState = ref<FlareState>('NO_FLARE')
const notes = ref('')
// questionId -> partial answer state
const answers = reactive<Record<number, { optionId: number | null; answerText: string; answerNumeric: number | null }>>({})
const loading = ref(true)
const loadFailed = ref(false)
const saved = ref(false)

onMounted(async () => {
  try {
    const q = await symptomApi.activeQuestionnaire()
    questionnaire.value = q
    for (const question of q.questions) {
      answers[question.id] = { optionId: null, answerText: '', answerNumeric: null }
    }
    try {
      const existing = await symptomApi.getCheckIn(checkInDate.value)
      if (existing.questionnaireVersionId === q.versionId) {
        flareState.value = existing.flareState
        notes.value = existing.notes ?? ''
        for (const a of existing.answers) {
          if (answers[a.questionId]) {
            answers[a.questionId] = {
              optionId: a.optionId,
              answerText: a.answerText ?? '',
              answerNumeric: a.answerNumeric,
            }
          }
        }
      } else {
        retiredCheckIn.value = existing
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        // no entry for this date yet — start blank
      } else {
        throw e
      }
    }
  } catch (e) {
    // Load failed: show the error and withhold the editor so a blind save
    // cannot wipe the day's existing data (the save is a replacing upsert).
    capture(e)
    loadFailed.value = true
  } finally {
    loading.value = false
  }
})

// v-model.number keeps the raw '' when a typed value is cleared; coerce it
// back to null so the payload matches the numeric DTO fields.
function numOrNull(v: number | null | '' | undefined): number | null {
  return v === '' || v === undefined ? null : v
}

async function save() {
  clear()
  saved.value = false
  const q = questionnaire.value
  if (!q) return
  const payload: AnswerRequest[] = q.questions
    .map((question) => ({
      questionId: question.id,
      optionId: answers[question.id].optionId,
      answerText: question.answerType === 'TEXT' ? answers[question.id].answerText || null : null,
      answerNumeric: question.answerType === 'NUMERIC' ? numOrNull(answers[question.id].answerNumeric) : null,
    }))
    .filter((a) => a.optionId !== null || a.answerText !== null || a.answerNumeric !== null)
  try {
    await symptomApi.saveCheckIn({
      checkInDate: checkInDate.value,
      questionnaireVersionId: q.versionId,
      flareState: flareState.value,
      answers: payload,
      notes: notes.value || undefined,
    })
    saved.value = true
    void useRedFlagsStore().refreshCurrent()
  } catch (e) {
    capture(e)
  }
}
</script>

<template>
  <section class="max-w-2xl">
    <h1 class="text-2xl font-semibold">{{ t('checkIn.title') }} — {{ checkInDate }}</h1>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <p v-else-if="loadFailed" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <div v-else-if="retiredCheckIn" class="mt-4 space-y-4">
      <p data-testid="retired-notice" class="rounded bg-amber-50 p-3 text-sm text-amber-800 dark:bg-amber-950 dark:text-amber-200">{{ t('checkIn.retiredVersionNotice') }}</p>
      <div class="space-y-2 rounded border bg-white p-4 dark:bg-gray-800">
        <p><span class="font-medium">{{ t('checkIn.flareState') }}:</span> {{ t(`checkIn.FlareState.${retiredCheckIn.flareState}`) }}</p>
        <p v-if="retiredCheckIn.totalSymptomScore !== null">
          <span class="font-medium">{{ t('checkIn.score') }}:</span> {{ retiredCheckIn.totalSymptomScore }}
        </p>
        <p v-if="retiredCheckIn.notes"><span class="font-medium">{{ t('checkIn.notes') }}:</span> {{ retiredCheckIn.notes }}</p>
      </div>
      <div v-for="a in retiredCheckIn.answers" :key="a.questionId" class="rounded border bg-white p-4 dark:bg-gray-800">
        <p class="font-medium">{{ a.label }}</p>
        <p class="mt-1 text-sm">{{ a.optionLabel ?? a.answerText ?? a.answerNumeric }}</p>
      </div>
    </div>
    <div v-else-if="questionnaire" class="mt-4 space-y-6">
      <p v-if="message" class="rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
      <p v-if="saved" class="rounded bg-green-50 p-3 text-sm text-green-700 dark:bg-green-950 dark:text-green-300">{{ t('common.saved') }}</p>

      <div>
        <label class="block text-sm font-medium" for="flare">{{ t('checkIn.flareState') }}</label>
        <select id="flare" v-model="flareState" class="mt-1 w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-800">
          <option v-for="f in flareOptions" :key="f" :value="f">{{ t(`checkIn.FlareState.${f}`) }}</option>
        </select>
      </div>

      <div v-for="question in questionnaire.questions" :key="question.id" class="rounded border bg-white p-4 dark:bg-gray-800">
        <p class="font-medium">{{ question.label }} <span v-if="question.required" class="text-red-500">*</span></p>
        <p v-if="question.helpText" class="mt-1 text-sm text-gray-500 dark:text-gray-400">{{ question.helpText }}</p>

        <div v-if="question.answerType === 'SINGLE_CHOICE'" class="mt-2 space-y-1">
          <label v-for="option in question.options" :key="option.id" class="flex items-center gap-2 text-sm">
            <input v-model="answers[question.id].optionId" type="radio" :name="`q-${question.id}`" :value="option.id" />
            {{ option.label }}
          </label>
        </div>
        <input v-else-if="question.answerType === 'NUMERIC'" v-model.number="answers[question.id].answerNumeric"
               type="number" :min="question.minNumericValue ?? undefined" :max="question.maxNumericValue ?? undefined"
               class="mt-2 w-32 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        <textarea v-else v-model="answers[question.id].answerText" rows="2" maxlength="1000"
                  class="mt-2 w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-800" />
      </div>

      <div>
        <label class="block text-sm font-medium" for="notes">{{ t('checkIn.notes') }}</label>
        <textarea id="notes" v-model="notes" rows="2" maxlength="1000"
                  class="mt-1 w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-800" />
      </div>

      <button data-testid="save" class="rounded bg-blue-600 px-6 py-2 text-white" @click="save">{{ t('common.save') }}</button>
    </div>
  </section>
</template>
