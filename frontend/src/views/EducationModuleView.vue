<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { educationApi } from '@/api/education'
import { useApiError } from '@/composables/useApiError'
import type { EducationLesson, EducationModuleDetail } from '@/types/api'

const { t } = useI18n()
const route = useRoute()
const { message, capture } = useApiError()

const moduleSlug = route.params.moduleSlug as string
const module = ref<EducationModuleDetail | null>(null)
const loading = ref(true)
const openLesson = ref<string | null>(null)

async function load() {
  module.value = await educationApi.getModule(moduleSlug)
  loading.value = false
  if (openLesson.value === null) {
    openLesson.value = module.value.lessons[0]?.lessonSlug ?? null
  }
}

onMounted(load)

async function toggleLesson(lesson: EducationLesson) {
  try {
    if (lesson.completed) {
      await educationApi.uncompleteLesson(moduleSlug, lesson.lessonSlug)
    } else {
      await educationApi.completeLesson(moduleSlug, lesson.lessonSlug)
    }
    await load()
  } catch (e) {
    capture(e)
  }
}
</script>

<template>
  <section class="max-w-3xl">
    <router-link to="/education" class="text-sm text-blue-600">← {{ t('education.backToModules') }}</router-link>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else-if="module">
      <h1 class="mt-2 text-2xl font-semibold">{{ module.title }}</h1>
      <p v-if="module.summary" class="mt-1 text-gray-600">{{ module.summary }}</p>
      <p class="mt-1 text-sm text-gray-500">
        {{ t('education.completedCount', { done: module.completedLessonCount ?? 0, count: module.lessonCount }) }}
      </p>
      <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>

      <h2 class="mt-6 font-medium">{{ t('education.lessons') }}</h2>
      <div class="mt-2 space-y-2">
        <div v-for="lesson in module.lessons" :key="lesson.lessonSlug" class="rounded border bg-white">
          <button class="flex w-full items-center justify-between p-4 text-left"
                  @click="openLesson = openLesson === lesson.lessonSlug ? null : lesson.lessonSlug">
            <span>
              {{ lesson.title }}
              <span v-if="lesson.completed" class="ml-2 rounded bg-green-100 px-2 py-0.5 text-xs text-green-700">
                {{ t('education.completedBadge') }}
              </span>
            </span>
            <span class="text-gray-400">{{ openLesson === lesson.lessonSlug ? '−' : '+' }}</span>
          </button>
          <div v-if="openLesson === lesson.lessonSlug" class="border-t p-4">
            <!-- bodyHtml is server-rendered from staff-authored, reviewed content; safe to render -->
            <div class="prose max-w-none" v-html="lesson.bodyHtml" />
            <button :data-testid="`lesson-toggle-${lesson.lessonSlug}`"
                    class="mt-4 rounded border px-3 py-1 text-sm"
                    @click="toggleLesson(lesson)">
              {{ lesson.completed ? t('education.markIncomplete') : t('education.markComplete') }}
            </button>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>
