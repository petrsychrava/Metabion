<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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
const { message, capture, clear } = useApiError()

const moduleSlug = route.params.moduleSlug as string
const version = Number(route.params.version)

const form = ref<EducationContentFormData | null>(null)
const lessons = ref<EducationContentLessonRow[]>([])
const loading = ref(true)
const saving = ref(false)
const initialSnapshot = ref('')

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

const hasIncompleteRows = computed(() => lessons.value.some(rowIncomplete))

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
  if (!form.value || hasIncompleteRows.value) return
  saving.value = true
  clear()
  try {
    await contentEducationApi.updateVersion(moduleSlug, version, { ...form.value, lessons: lessons.value.filter(rowPopulated) })
    initialSnapshot.value = JSON.stringify(snapshot())
    await router.push(`/clinical/content/${moduleSlug}/${version}`)
  } catch (e) {
    // Only a state-change 400 (no field errors) means the version left the editable state; resync then.
    // Validation 400s carry a fields map: keep the author's edits intact and just show the banner.
    if (e instanceof ApiError && e.status === 400 && !e.fields) {
      await load()
      capture(e)
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
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>

    <form v-else-if="form" class="mt-4 space-y-4" @submit.prevent="save">
      <div class="grid gap-3 sm:grid-cols-2">
        <label class="text-sm">{{ t('clinical.content.fields.topic') }}
          <input v-model="form.topic" data-testid="topic" type="text"
                 class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        </label>
        <label class="text-sm">{{ t('clinical.content.fields.sortOrder') }}
          <input v-model.number="form.sortOrder" data-testid="sort-order" type="number" min="1"
                 class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        </label>
        <label class="text-sm">{{ t('clinical.content.fields.englishTitle') }}
          <input v-model="form.englishTitle" data-testid="english-title" type="text"
                 class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        </label>
        <label class="text-sm">{{ t('clinical.content.fields.czechTitle') }}
          <input v-model="form.czechTitle" data-testid="czech-title" type="text"
                 class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        </label>
        <label class="text-sm sm:col-span-2">{{ t('clinical.content.fields.englishSummary') }}
          <textarea v-model="form.englishSummary" data-testid="english-summary" rows="2"
                    class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
        </label>
        <label class="text-sm sm:col-span-2">{{ t('clinical.content.fields.czechSummary') }}
          <textarea v-model="form.czechSummary" data-testid="czech-summary" rows="2"
                    class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
        </label>
      </div>

      <h2 class="font-medium">{{ t('clinical.content.editor.lessons') }}</h2>
      <div v-for="(lesson, index) in lessons" :key="index" data-testid="lesson-row"
           class="space-y-3 rounded border p-3">
        <div class="flex items-end justify-between gap-3">
          <label class="flex-1 text-sm">{{ t('clinical.content.editor.lessonSlug') }}
            <input v-model="lesson.slug" :data-testid="`lesson-slug-${index}`" type="text"
                   class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
          </label>
          <label class="w-28 text-sm">{{ t('clinical.content.fields.sortOrder') }}
            <input v-model.number="lesson.sortOrder" :data-testid="`lesson-sort-${index}`" type="number" min="1"
                   class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
          </label>
          <button type="button" :data-testid="`remove-lesson-${index}`"
                  class="rounded border px-2 py-1 text-sm" @click="lessons.splice(index, 1)">
            {{ t('common.remove') }}
          </button>
        </div>
        <p v-if="rowIncomplete(lesson)" class="text-sm text-red-600 dark:text-red-400">
          {{ t('clinical.content.editor.lessonIncomplete') }}
        </p>
        <div class="grid gap-3 sm:grid-cols-2">
          <div>
            <label class="text-sm">{{ t('clinical.content.fields.englishTitle') }}
              <input v-model="lesson.englishTitle" :data-testid="`english-title-${index}`" type="text"
                     class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
            </label>
            <label class="mt-2 block text-sm">{{ t('clinical.content.fields.englishSummary') }}
              <textarea v-model="lesson.englishSummary" :data-testid="`english-summary-${index}`" rows="2"
                        class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
            </label>
            <div class="mt-2">
              <p class="text-sm">{{ t('clinical.content.fields.englishBody') }}</p>
              <MarkdownEditor v-model="lesson.englishBodyMarkdown" />
            </div>
          </div>
          <div>
            <label class="text-sm">{{ t('clinical.content.fields.czechTitle') }}
              <input v-model="lesson.czechTitle" :data-testid="`czech-title-${index}`" type="text"
                     class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
            </label>
            <label class="mt-2 block text-sm">{{ t('clinical.content.fields.czechSummary') }}
              <textarea v-model="lesson.czechSummary" :data-testid="`czech-summary-${index}`" rows="2"
                        class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
            </label>
            <div class="mt-2">
              <p class="text-sm">{{ t('clinical.content.fields.czechBody') }}</p>
              <MarkdownEditor v-model="lesson.czechBodyMarkdown" />
            </div>
          </div>
        </div>
      </div>

      <button type="button" data-testid="add-lesson" class="rounded border px-3 py-1 text-sm" @click="addLesson">
        {{ t('clinical.content.editor.addLesson') }}
      </button>

      <div class="flex gap-2">
        <button type="submit" data-testid="save" :disabled="saving || hasIncompleteRows"
                class="rounded bg-blue-600 px-3 py-1 text-sm text-white disabled:opacity-50">
          {{ t('common.save') }}
        </button>
        <router-link :to="`/clinical/content/${moduleSlug}/${version}`"
                     class="rounded border px-3 py-1 text-sm">{{ t('common.cancel') }}</router-link>
      </div>
    </form>
  </section>
</template>
