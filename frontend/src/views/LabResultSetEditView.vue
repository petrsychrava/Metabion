<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@/api/http'
import { labApi } from '@/api/labs'
import { useApiError } from '@/composables/useApiError'
import FieldError from '@/components/FieldError.vue'
import type { LabResultRequest, LabResultSetResponse, LabTestDefinition } from '@/types/api'

const { t } = useI18n()
const route = useRoute()
const { message, fieldErrors, capture, clear } = useApiError()

const id = computed(() => (route.params.id ? Number(route.params.id) : null))
const isNew = computed(() => id.value === null)

const tests = ref<LabTestDefinition[]>([])
const collectionDate = ref('')
const notes = ref('')
const version = ref<number | null>(null)
const results = reactive<LabResultRequest[]>([])
const loading = ref(true)
const saved = ref(false)
const conflict = ref(false)

function newResult(): LabResultRequest {
  const first = tests.value[0]
  return { testCode: first?.code ?? '', value: 0, unit: first?.canonicalUnit ?? '', referenceLower: null, referenceUpper: null }
}

async function loadExisting() {
  if (id.value === null) {
    collectionDate.value = new Date().toISOString().slice(0, 10)
    return
  }
  const set: LabResultSetResponse = await labApi.getResultSet(id.value)
  collectionDate.value = set.collectionDate
  notes.value = set.notes ?? ''
  version.value = set.version
  results.splice(0, results.length, ...set.results.map((r) => ({
    testCode: r.testCode,
    value: r.reportedValue,
    unit: r.reportedUnit,
    referenceLower: r.referenceLower,
    referenceUpper: r.referenceUpper,
  })))
}

async function reload() {
  conflict.value = false
  clear()
  loading.value = true
  try {
    await loadExisting()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  tests.value = await labApi.listTests()
  await loadExisting()
  loading.value = false
})

function onTestChange(result: LabResultRequest) {
  const def = tests.value.find((d) => d.code === result.testCode)
  if (def) result.unit = def.canonicalUnit
}

async function save() {
  clear()
  saved.value = false
  conflict.value = false
  try {
    const payload = {
      resultSetId: isNew.value ? null : id.value,
      version: isNew.value ? null : version.value,
      collectionDate: collectionDate.value,
      notes: notes.value || undefined,
      results,
    }
    if (isNew.value) {
      await labApi.createResultSet(payload)
    } else {
      await labApi.updateResultSet(id.value!, payload)
    }
    saved.value = true
  } catch (e) {
    if (e instanceof ApiError && e.status === 409) {
      conflict.value = true
      message.value = t('errors.conflict')
      return
    }
    capture(e)
  }
}
</script>

<template>
  <section class="max-w-3xl">
    <h1 class="text-2xl font-semibold">{{ isNew ? t('labs.newResultSet') : t('labs.edit') }}</h1>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <div v-else class="mt-4 space-y-4">
      <p v-if="message" class="rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
      <p v-if="saved" class="rounded bg-green-50 p-3 text-sm text-green-700">{{ t('common.saved') }}</p>
      <button v-if="conflict" data-testid="reload" class="rounded border px-3 py-1 text-sm" @click="reload">
        {{ t('labs.reload') }}
      </button>

      <div class="grid gap-4 rounded border bg-white p-4 sm:grid-cols-2">
        <div>
          <label class="block text-sm font-medium" for="colDate">{{ t('labs.collectionDate') }}</label>
          <input id="colDate" v-model="collectionDate" type="date" required
                 class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
          <FieldError :message="fieldErrors.collectionDate" />
        </div>
        <div>
          <label class="block text-sm font-medium" for="notes">{{ t('labs.notes') }}</label>
          <input id="notes" v-model="notes" type="text" maxlength="2000"
                 class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        </div>
      </div>

      <div class="rounded border bg-white p-4">
        <div class="flex items-center justify-between">
          <h2 class="font-medium">{{ t('labs.results') }}</h2>
          <button data-testid="add-result" class="rounded border px-3 py-1 text-sm"
                  @click="results.push(newResult())">{{ t('labs.addResult') }}</button>
        </div>
        <div v-for="(r, i) in results" :key="i" class="mt-3 grid gap-2 border-t pt-3 sm:grid-cols-[1fr_6rem_6rem_6rem_6rem_auto]">
          <select v-model="r.testCode" class="rounded border border-gray-300 px-2 py-1" @change="onTestChange(r)">
            <option v-for="def in tests" :key="def.code" :value="def.code">{{ def.label }}</option>
          </select>
          <input v-model.number="r.value" :data-testid="`result-value-${i}`" type="number" step="any" min="0"
                 class="rounded border border-gray-300 px-2 py-1" />
          <select v-model="r.unit" class="rounded border border-gray-300 px-2 py-1">
            <option v-for="u in tests.find((d) => d.code === r.testCode)?.allowedUnits ?? [r.unit]" :key="u" :value="u">{{ u }}</option>
          </select>
          <input v-model.number="r.referenceLower" type="number" step="any" min="0" :placeholder="t('labs.referenceLower')"
                 class="rounded border border-gray-300 px-2 py-1" />
          <input v-model.number="r.referenceUpper" type="number" step="any" min="0" :placeholder="t('labs.referenceUpper')"
                 class="rounded border border-gray-300 px-2 py-1" />
          <button class="text-sm text-red-600" @click="results.splice(i, 1)">{{ t('common.remove') }}</button>
        </div>
      </div>

      <button data-testid="save" class="rounded bg-blue-600 px-6 py-2 text-white" @click="save">{{ t('common.save') }}</button>
    </div>
  </section>
</template>
