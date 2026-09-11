<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import FieldError from '@/components/FieldError.vue'
import { contentEducationApi } from '@/api/contentEducation'

const props = defineProps<{
  modelValue: string
  error?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const { t } = useI18n()
const tab = ref<'edit' | 'preview'>('edit')
const html = ref('')
const loadingPreview = ref(false)

async function showPreview() {
  tab.value = 'preview'
  if (!props.modelValue.trim()) {
    html.value = ''
    return
  }
  loadingPreview.value = true
  try {
    html.value = (await contentEducationApi.previewMarkdown(props.modelValue)).html
  } catch {
    html.value = ''
  } finally {
    loadingPreview.value = false
  }
}

// Edits invalidate a stale preview; force the author back onto the edit tab.
watch(() => props.modelValue, () => {
  if (tab.value === 'preview') tab.value = 'edit'
})
</script>

<template>
  <div>
    <div class="flex gap-3 text-sm">
      <button type="button" data-testid="markdown-edit-tab"
              :class="tab === 'edit' ? 'font-semibold' : 'text-gray-500'"
              @click="tab = 'edit'">{{ t('clinical.content.editor.editTab') }}</button>
      <button type="button" data-testid="markdown-preview-tab"
              :class="tab === 'preview' ? 'font-semibold' : 'text-gray-500'"
              @click="showPreview">{{ t('clinical.content.editor.previewTab') }}</button>
    </div>
    <textarea v-if="tab === 'edit'" :value="modelValue" rows="8" data-testid="markdown-source"
              class="mt-1 w-full rounded border border-gray-300 px-2 py-1 font-mono text-sm dark:border-gray-600 dark:bg-gray-800"
              @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"></textarea>
    <div v-else class="prose mt-1 max-w-none rounded border border-gray-300 p-2 dark:border-gray-600">
      <p v-if="loadingPreview" class="text-sm text-gray-500">{{ t('common.loading') }}</p>
      <!-- html is rendered by the server-side EducationMarkdownService from staff-authored content -->
      <div v-else v-html="html" />
    </div>
    <p class="mt-1 text-xs text-gray-500 dark:text-gray-400">{{ t('clinical.content.editor.markdownHint') }}</p>
    <FieldError :message="error" />
  </div>
</template>
