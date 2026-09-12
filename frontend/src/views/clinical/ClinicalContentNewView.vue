<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import FieldError from '@/components/FieldError.vue'
import { contentEducationApi } from '@/api/contentEducation'
import { useApiError } from '@/composables/useApiError'

const { t } = useI18n()
const router = useRouter()
const { message, fieldErrors, capture, clear } = useApiError()

const form = reactive({
  slug: '',
  topic: '',
  sortOrder: 10,
  englishTitle: '',
  englishSummary: '',
  czechTitle: '',
  czechSummary: '',
})
const saving = ref(false)

// The server persists a Czech module localization only when both values are present, so
// one-sided input would otherwise be silently dropped after a successful create.
const czechModuleIncomplete = computed(() => {
  const filled = [form.czechTitle, form.czechSummary].filter((value) => !!value?.trim()).length
  return filled === 1
})

async function submit() {
  if (czechModuleIncomplete.value) return
  saving.value = true
  clear()
  try {
    const created = await contentEducationApi.createModule({
      slug: form.slug.trim(),
      topic: form.topic.trim(),
      sortOrder: form.sortOrder,
      englishTitle: form.englishTitle.trim(),
      englishSummary: form.englishSummary.trim(),
      czechTitle: form.czechTitle.trim() || null,
      czechSummary: form.czechSummary.trim() || null,
    })
    await router.push(`/clinical/content/${created.moduleSlug}/${created.version}/edit`)
  } catch (e) {
    capture(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="max-w-xl">
    <router-link to="/clinical/content" class="text-sm text-blue-600 dark:text-blue-400">
      ← {{ t('clinical.content.backToList') }}
    </router-link>
    <h1 class="mt-2 text-2xl font-semibold">{{ t('clinical.content.create.title') }}</h1>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>

    <form class="mt-4 space-y-3" @submit.prevent="submit">
      <label class="block text-sm">{{ t('clinical.content.fields.slug') }}
        <input v-model="form.slug" data-testid="slug" type="text" required
               class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        <FieldError :message="fieldErrors.slug" />
      </label>
      <label class="block text-sm">{{ t('clinical.content.fields.topic') }}
        <input v-model="form.topic" data-testid="topic" type="text" required
               class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        <FieldError :message="fieldErrors.topic" />
      </label>
      <label class="block text-sm">{{ t('clinical.content.fields.sortOrder') }}
        <input v-model.number="form.sortOrder" data-testid="sort-order" type="number" min="1" required
               class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        <FieldError :message="fieldErrors.sortOrder" />
      </label>
      <label class="block text-sm">{{ t('clinical.content.fields.englishTitle') }}
        <input v-model="form.englishTitle" data-testid="english-title" type="text" required
               class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        <FieldError :message="fieldErrors.englishTitle" />
      </label>
      <label class="block text-sm">{{ t('clinical.content.fields.englishSummary') }}
        <textarea v-model="form.englishSummary" data-testid="english-summary" rows="3" required
                  class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
        <FieldError :message="fieldErrors.englishSummary" />
      </label>
      <label class="block text-sm">{{ t('clinical.content.fields.czechTitle') }}
        <input v-model="form.czechTitle" data-testid="czech-title" type="text"
               class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        <FieldError :message="fieldErrors.czechTitle" />
      </label>
      <label class="block text-sm">{{ t('clinical.content.fields.czechSummary') }}
        <textarea v-model="form.czechSummary" data-testid="czech-summary" rows="3"
                  class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
        <FieldError :message="fieldErrors.czechSummary" />
      </label>
      <p v-if="czechModuleIncomplete" data-testid="czech-module-incomplete"
         class="text-sm text-red-600 dark:text-red-400">
        {{ t('clinical.content.editor.czechModuleIncomplete') }}
      </p>

      <button type="submit" data-testid="create" :disabled="saving || czechModuleIncomplete"
              class="rounded bg-blue-600 px-3 py-1 text-sm text-white disabled:opacity-50">
        {{ t('clinical.content.create.submit') }}
      </button>
    </form>
  </section>
</template>
