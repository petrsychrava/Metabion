<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { educationApi } from '@/api/education'
import { useApiError } from '@/composables/useApiError'
import type { EducationModuleSummary } from '@/types/api'

const { t, locale } = useI18n()
const { message, capture } = useApiError()
const modules = ref<EducationModuleSummary[]>([])
const loading = ref(true)

async function load() {
  try {
    modules.value = await educationApi.listModules()
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
// Module titles/summaries are localized server-side; refetch after a language switch.
watch(locale, load)
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('education.title') }}</h1>
    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <div v-else class="mt-4 grid gap-4 sm:grid-cols-2">
      <router-link v-for="m in modules" :key="m.moduleSlug" :to="`/education/${m.moduleSlug}`"
                   class="rounded border bg-white p-4 hover:border-blue-400 dark:bg-gray-800">
        <h2 class="font-medium">{{ m.title }}</h2>
        <p v-if="m.summary" class="mt-1 text-sm text-gray-600 dark:text-gray-400">{{ m.summary }}</p>
        <p class="mt-2 text-sm text-gray-500 dark:text-gray-400">
          {{ t('education.completedCount', { done: m.completedLessonCount ?? 0, count: m.lessonCount }) }}
          <span v-if="m.completed" class="ml-2 rounded bg-green-100 px-2 py-0.5 text-xs text-green-700 dark:bg-green-900 dark:text-green-300">
            {{ t('education.completedBadge') }}
          </span>
        </p>
      </router-link>
    </div>
  </section>
</template>
