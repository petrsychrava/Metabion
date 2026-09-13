<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import MarkdownEditor from '@/components/MarkdownEditor.vue'
import { contentEducationApi } from '@/api/contentEducation'
import { ApiError } from '@/api/http'
import { useApiError } from '@/composables/useApiError'
import type { EducationContentFormData, EducationContentLessonRow } from '@/types/api'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { message, fieldErrors, capture, clear } = useApiError()

const moduleSlug = route.params.moduleSlug as string
const version = Number(route.params.version)

const form = ref<EducationContentFormData | null>(null)
const lessons = ref<EducationContentLessonRow[]>([])
const loading = ref(true)
const saving = ref(false)
const initialSnapshot = ref('')
const stateConflict = ref(false)

let unmounted = false
onUnmounted(() => {
  unmounted = true
})

function snapshot(): unknown {
  return form.value ? { ...form.value, lessons: lessons.value } : null
}

const dirty = computed(() => form.value !== null && JSON.stringify(snapshot()) !== initialSnapshot.value)

function rowPopulated(row: EducationContentLessonRow): boolean {
  return !!(row.slug || row.englishTitle || row.englishSummary || row.englishBodyMarkdown
    || row.czechTitle || row.czechSummary || row.czechBodyMarkdown)
}

function rowIncomplete(row: EducationContentLessonRow): boolean {
  return rowPopulated(row)
    && (!row.slug.trim() || row.sortOrder < 1 || !row.englishTitle.trim()
        || !row.englishSummary.trim() || !row.englishBodyMarkdown.trim())
}

// The server persists a Czech localization only when all three values are present
// (addOptionalLessonLocalization / addOptionalModuleLocalization), so partial Czech input
// would otherwise be silently dropped on save.
function czechLessonIncomplete(row: EducationContentLessonRow): boolean {
  const filled = [row.czechTitle, row.czechSummary, row.czechBodyMarkdown]
    .filter((value) => !!value?.trim()).length
  return filled > 0 && filled < 3
}

const hasIncompleteRows = computed(() => lessons.value.some(rowIncomplete))
const hasCzechLessonGaps = computed(() => lessons.value.some(czechLessonIncomplete))
const czechModuleIncomplete = computed(() => {
  const filled = [form.value?.czechTitle, form.value?.czechSummary]
    .filter((value) => !!value?.trim()).length
  return filled === 1
})
const saveDisabled = computed(() =>
  hasIncompleteRows.value || hasCzechLessonGaps.value || czechModuleIncomplete.value)

function nextSortOrder(): number {
  return lessons.value.reduce((max, row) => Math.max(max, row.sortOrder), 0) + 10
}

function addLesson() {
  lessons.value.push({
    slug: '', sortOrder: nextSortOrder(), englishTitle: '', englishSummary: '', englishBodyMarkdown: '',
    czechTitle: '', czechSummary: '', czechBodyMarkdown: '',
  })
}

async function load() {
  clear()
  loading.value = true
  try {
    const data = await contentEducationApi.getVersionForm(moduleSlug, version)
    form.value = data
    lessons.value = data.lessons.map((row) => ({ ...row }))
    initialSnapshot.value = JSON.stringify(snapshot())
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.value || saveDisabled.value || stateConflict.value) return
  saving.value = true
  clear()
  // Freeze the snapshot of exactly what is being submitted; edits made while the PUT is in
  // flight must stay dirty so the leave guard prompts instead of silently discarding them.
  const submitted = JSON.stringify(snapshot())
  const originPath = route.path
  try {
    await contentEducationApi.updateVersion(moduleSlug, version, { ...form.value, lessons: lessons.value.filter(rowPopulated) })
    initialSnapshot.value = submitted
    // The author may have left while the PUT was in flight; only redirect when this
    // editor is still the current route and this instance is still mounted.
    if (unmounted || route.path !== originPath) return
    await router.push(`/clinical/content/${moduleSlug}/${version}`)
  } catch (e) {
    // Only a state-change 400 (no field errors) means the version left the editable state; bail
    // to the detail page, which shows the current status and valid actions. Validation 400s carry
    // a fields map: keep the author's edits intact and just show the banner.
    if (e instanceof ApiError && e.status === 400 && !e.fields) {
      // A departed editor must not yank the author back; the push IS the bail-out departure.
      if (unmounted || route.path !== originPath) return
      await router.push(`/clinical/content/${moduleSlug}/${version}`)
      if (unmounted || route.path !== originPath) return
      // The dirty guard vetoed the forced exit (Vue Router aborts without throwing). The
      // version can no longer be saved, so lock the editor and surface the state change
      // instead of leaving a dead, enabled form whose every save fails silently.
      stateConflict.value = true
      capture(e)
      message.value = t('clinical.content.editor.stateConflict')
    } else {
      capture(e)
    }
  } finally {
    saving.value = false
  }
}

onBeforeRouteLeave(() => {
  if (dirty.value && !window.confirm(t('clinical.content.editor.unsaved'))) return false
  return true
})

onMounted(load)
</script>

<template>
  <section class="max-w-3xl">
    <router-link :to="`/clinical/content/${moduleSlug}/${version}`" class="text-sm text-blue-600 dark:text-blue-400">
      ← {{ t('common.back') }}
    </router-link>
    <h1 class="mt-2 text-2xl font-semibold">{{ t('clinical.content.editor.title') }}</h1>
    <p class="mt-1 text-sm text-gray-600 dark:text-gray-400">{{ form?.slug }} v{{ version }}</p>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <ul v-if="Object.keys(fieldErrors).length > 0" data-testid="field-errors"
        class="mt-2 list-inside list-disc rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">
      <li v-for="(fieldMessage, field) in fieldErrors" :key="field">
        <code>{{ field }}</code>: {{ fieldMessage }}
      </li>
    </ul>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>

    <form v-else-if="form" class="mt-4 space-y-4" @submit.prevent="save">
      <div class="grid gap-3 sm:grid-cols-2">
        <label class="text-sm">{{ t('clinical.content.fields.topic') }}
          <input v-model="form.topic" data-testid="topic" type="text" disabled
                 class="mt-1 w-full rounded border border-gray-300 px-2 py-1 disabled:opacity-60 dark:border-gray-600 dark:bg-gray-800" />
        </label>
        <label class="text-sm">{{ t('clinical.content.fields.sortOrder') }}
          <input v-model.number="form.sortOrder" data-testid="sort-order" type="number" min="1" disabled
                 class="mt-1 w-full rounded border border-gray-300 px-2 py-1 disabled:opacity-60 dark:border-gray-600 dark:bg-gray-800" />
        </label>
        <label class="text-sm">{{ t('clinical.content.fields.englishTitle') }}
          <input v-model="form.englishTitle" data-testid="english-title" type="text" :disabled="stateConflict"
                 class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        </label>
        <label class="text-sm">{{ t('clinical.content.fields.czechTitle') }}
          <input v-model="form.czechTitle" data-testid="czech-title" type="text" :disabled="stateConflict"
                 class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        </label>
        <label class="text-sm sm:col-span-2">{{ t('clinical.content.fields.englishSummary') }}
          <textarea v-model="form.englishSummary" data-testid="english-summary" rows="2" :disabled="stateConflict"
                    class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
        </label>
        <label class="text-sm sm:col-span-2">{{ t('clinical.content.fields.czechSummary') }}
          <textarea v-model="form.czechSummary" data-testid="czech-summary" rows="2" :disabled="stateConflict"
                    class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
        </label>
        <p v-if="czechModuleIncomplete" data-testid="czech-module-incomplete"
           class="text-sm text-red-600 sm:col-span-2 dark:text-red-400">
          {{ t('clinical.content.editor.czechModuleIncomplete') }}
        </p>
      </div>

      <h2 class="font-medium">{{ t('clinical.content.editor.lessons') }}</h2>
      <div v-for="(lesson, index) in lessons" :key="index" data-testid="lesson-row"
           class="space-y-3 rounded border p-3">
        <div class="flex items-end justify-between gap-3">
          <label class="flex-1 text-sm">{{ t('clinical.content.editor.lessonSlug') }}
            <input v-model="lesson.slug" :data-testid="`lesson-slug-${index}`" type="text" :disabled="stateConflict"
                   class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
          </label>
          <label class="w-28 text-sm">{{ t('clinical.content.fields.sortOrder') }}
            <input v-model.number="lesson.sortOrder" :data-testid="`lesson-sort-${index}`" type="number" min="1"
                   :disabled="stateConflict"
                   class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
          </label>
          <button type="button" :data-testid="`remove-lesson-${index}`" :disabled="stateConflict"
                  class="rounded border px-2 py-1 text-sm" @click="lessons.splice(index, 1)">
            {{ t('common.remove') }}
          </button>
        </div>
        <p v-if="rowIncomplete(lesson)" class="text-sm text-red-600 dark:text-red-400">
          {{ t('clinical.content.editor.lessonIncomplete') }}
        </p>
        <p v-if="czechLessonIncomplete(lesson)" class="text-sm text-red-600 dark:text-red-400">
          {{ t('clinical.content.editor.czechIncomplete') }}
        </p>
        <div class="grid gap-3 sm:grid-cols-2">
          <div>
            <label class="text-sm">{{ t('clinical.content.fields.englishTitle') }}
              <input v-model="lesson.englishTitle" :data-testid="`english-title-${index}`" type="text"
                     :disabled="stateConflict"
                     class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
            </label>
            <label class="mt-2 block text-sm">{{ t('clinical.content.fields.englishSummary') }}
              <textarea v-model="lesson.englishSummary" :data-testid="`english-summary-${index}`" rows="2"
                        :disabled="stateConflict"
                        class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
            </label>
            <div class="mt-2">
              <p class="text-sm">{{ t('clinical.content.fields.englishBody') }}</p>
              <MarkdownEditor v-model="lesson.englishBodyMarkdown" :disabled="stateConflict" />
            </div>
          </div>
          <div>
            <label class="text-sm">{{ t('clinical.content.fields.czechTitle') }}
              <input v-model="lesson.czechTitle" :data-testid="`czech-title-${index}`" type="text"
                     :disabled="stateConflict"
                     class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
            </label>
            <label class="mt-2 block text-sm">{{ t('clinical.content.fields.czechSummary') }}
              <textarea v-model="lesson.czechSummary" :data-testid="`czech-summary-${index}`" rows="2"
                        :disabled="stateConflict"
                        class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
            </label>
            <div class="mt-2">
              <p class="text-sm">{{ t('clinical.content.fields.czechBody') }}</p>
              <MarkdownEditor v-model="lesson.czechBodyMarkdown" :disabled="stateConflict" />
            </div>
          </div>
        </div>
      </div>

      <button type="button" data-testid="add-lesson" class="rounded border px-3 py-1 text-sm"
              :disabled="stateConflict" @click="addLesson">
        {{ t('clinical.content.editor.addLesson') }}
      </button>

      <div class="flex gap-2">
        <button type="submit" data-testid="save" :disabled="saving || saveDisabled || stateConflict"
                class="rounded bg-blue-600 px-3 py-1 text-sm text-white disabled:opacity-50">
          {{ t('common.save') }}
        </button>
        <router-link :to="`/clinical/content/${moduleSlug}/${version}`"
                     class="rounded border px-3 py-1 text-sm">{{ t('common.cancel') }}</router-link>
      </div>
    </form>
  </section>
</template>
