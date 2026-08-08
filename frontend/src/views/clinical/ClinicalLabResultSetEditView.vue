<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { labApi } from '@/api/labs'
import { ApiError } from '@/api/http'
import { useApiError } from '@/composables/useApiError'
import FieldError from '@/components/FieldError.vue'
import type { LabTestDefinition } from '@/types/api'

interface ResultRow {
  testCode: string
  value: number | null
  unit: string
  referenceLower: number | null
  referenceUpper: number | null
}

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { message, fieldErrors, capture, clear } = useApiError()

const patientProfileId = Number(route.params.patientProfileId)
const id = ref<number | null>(route.params.resultSetId ? Number(route.params.resultSetId) : null)
const isNew = computed(() => id.value === null)

const tests = ref<LabTestDefinition[]>([])
const collectionDate = ref('')
const notes = ref('')
const version = ref<number | null>(null)
const results = reactive<ResultRow[]>([])
const loading = ref(true)
const saved = ref(false)
const conflict = ref(false)
const removalReason = ref('')
const saving = ref(false)

// v-model.number keeps the raw '' when a typed value is cleared; coerce it
// back to null so the payload matches the numeric DTO fields.
function numOrNull(v: number | null | '' | undefined): number | null {
  return v === '' || v === null || v === undefined || Number.isNaN(v) ? null : v
}

function onTestChange(row: ResultRow) {
  const def = tests.value.find((test) => test.code === row.testCode)
  if (def) row.unit = def.allowedUnits[0] ?? ''
}

function allowedUnits(testCode: string): string[] {
  return tests.value.find((test) => test.code === testCode)?.allowedUnits ?? []
}

async function loadExisting() {
  // New sets start with an empty collection date: the backend validates it
  // against the patient's timezone, where the staff browser's "today" can
  // already be tomorrow — and the lab report's date is what belongs here.
  if (id.value === null) {
    return
  }
  const existing = await clinicalApi.getLabResultSet(patientProfileId, id.value)
  collectionDate.value = existing.collectionDate
  notes.value = existing.notes ?? ''
  version.value = existing.version
  results.splice(0, results.length, ...existing.results.map((r) => ({
    testCode: r.testCode,
    value: r.reportedValue,
    unit: r.reportedUnit,
    referenceLower: r.referenceLower,
    referenceUpper: r.referenceUpper,
  })))
}

onMounted(async () => {
  try {
    tests.value = await labApi.listTests()
  } catch (e) {
    capture(e)
  }
  try {
    await loadExisting()
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
})

function addResult() {
  const first = tests.value[0]
  results.push({
    testCode: first?.code ?? '',
    value: null,
    unit: first?.allowedUnits[0] ?? '',
    referenceLower: null,
    referenceUpper: null,
  })
}

async function reload() {
  conflict.value = false
  clear()
  loading.value = true
  try {
    await loadExisting()
  } catch (e) {
    // Keep the conflict state (and its reload button) until a reload succeeds.
    capture(e)
    conflict.value = true
  } finally {
    loading.value = false
  }
}

async function save() {
  // Guard against double-submit: two in-flight creates would persist duplicates.
  if (saving.value) return
  clear()
  saved.value = false
  conflict.value = false
  saving.value = true
  try {
    const payload = {
      resultSetId: isNew.value ? null : id.value,
      version: isNew.value ? null : version.value,
      collectionDate: collectionDate.value,
      notes: notes.value || undefined,
      results: results.map((r) => ({
        testCode: r.testCode,
        value: numOrNull(r.value) as number,
        unit: r.unit,
        referenceLower: numOrNull(r.referenceLower),
        referenceUpper: numOrNull(r.referenceUpper),
      })),
    }
    if (isNew.value) {
      const res = await clinicalApi.createLabResultSet(patientProfileId, payload)
      version.value = res.version
      id.value = res.id
      // Switch into edit mode so a second Save updates instead of duplicating.
      await router.replace({ path: `/clinical/patients/${patientProfileId}/labs/${res.id}` })
    } else {
      const res = await clinicalApi.updateLabResultSet(patientProfileId, id.value!, payload)
      version.value = res.version
    }
    saved.value = true
  } catch (e) {
    if (e instanceof ApiError && e.status === 409) {
      conflict.value = true
      message.value = t('errors.conflict')
      return
    }
    capture(e)
  } finally {
    saving.value = false
  }
}

async function requestRemoval() {
  clear()
  try {
    await clinicalApi.requestLabRemoval(patientProfileId, id.value!, version.value!, removalReason.value)
    await router.push({ path: `/clinical/patients/${patientProfileId}/labs` })
  } catch (e) {
    capture(e)
  }
}
</script>

<template>
  <section class="mt-4">
    <h2 class="text-lg font-medium">{{ isNew ? t('labs.newResultSet') : t('labs.edit') }}</h2>

    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else>
      <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
      <p v-if="saved" class="mt-4 rounded bg-green-50 p-3 text-sm text-green-700 dark:bg-green-950 dark:text-green-300">{{ t('common.saved') }}</p>
      <button v-if="conflict" data-testid="reload" class="mt-2 rounded border px-3 py-1 text-sm" @click="reload">
        {{ t('labs.reload') }}
      </button>

      <form class="mt-4 space-y-4" @submit.prevent="save">
        <div>
          <label class="block text-sm font-medium">{{ t('labs.collectionDate') }}</label>
          <input v-model="collectionDate" type="date" required :disabled="saving"
                 class="mt-1 rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-800" />
          <FieldError :message="fieldErrors.collectionDate" />
        </div>
        <div>
          <label class="block text-sm font-medium">{{ t('labs.notes') }}</label>
          <input v-model="notes" type="text" :disabled="saving"
                 class="mt-1 w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-800" />
        </div>

        <h3 class="text-sm font-medium">{{ t('labs.results') }}</h3>
        <div v-for="(result, index) in results" :key="index" class="flex flex-wrap items-end gap-2">
          <label class="text-sm">{{ t('labs.test') }}
            <select v-model="result.testCode" :disabled="saving" @change="onTestChange(result)"
                    class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
              <option v-for="test in tests" :key="test.code" :value="test.code">{{ test.label }}</option>
            </select>
          </label>
          <label class="text-sm">{{ t('labs.value') }}
            <input v-model.number="result.value" type="number" step="any" :disabled="saving" :data-testid="`result-value-${index}`"
                   class="ml-1 w-28 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
          </label>
          <label class="text-sm">{{ t('labs.unit') }}
            <select v-model="result.unit" :disabled="saving"
                    class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
              <option v-for="unit in allowedUnits(result.testCode)" :key="unit" :value="unit">{{ unit }}</option>
            </select>
          </label>
          <label class="text-sm">{{ t('labs.referenceLower') }}
            <input v-model.number="result.referenceLower" type="number" step="any" :disabled="saving"
                   class="ml-1 w-24 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
          </label>
          <label class="text-sm">{{ t('labs.referenceUpper') }}
            <input v-model.number="result.referenceUpper" type="number" step="any" :disabled="saving"
                   class="ml-1 w-24 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
          </label>
          <button type="button" :disabled="saving" :data-testid="`remove-result-${index}`"
                  class="text-sm text-red-600 dark:text-red-400" @click="results.splice(index, 1)">
            {{ t('common.remove') }}
          </button>
        </div>
        <button type="button" data-testid="add-result" :disabled="saving" class="rounded border px-3 py-1 text-sm" @click="addResult">
          {{ t('labs.addResult') }}
        </button>

        <div>
          <button type="button" data-testid="save" :disabled="saving"
                  class="rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50" @click="save">
            {{ t('common.save') }}
          </button>
        </div>
      </form>

      <div v-if="!isNew" class="mt-6 rounded border border-red-200 p-4 dark:border-red-900">
        <label class="block text-sm font-medium">{{ t('labs.removalReason') }}</label>
        <input v-model="removalReason" type="text" data-testid="removal-reason"
               class="mt-1 w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-800" />
        <button data-testid="remove" class="mt-2 rounded border border-red-300 px-3 py-1 text-sm text-red-700 dark:text-red-300"
                @click="requestRemoval">
          {{ t('labs.requestRemoval') }}
        </button>
      </div>
    </template>
  </section>
</template>
