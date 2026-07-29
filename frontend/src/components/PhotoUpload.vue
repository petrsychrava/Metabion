<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { dietLogApi } from '@/api/dietLogs'
import { useApiError } from '@/composables/useApiError'
import type { DietLogPhotoUploadResponse } from '@/types/api'

const emit = defineEmits<{ uploaded: [photo: DietLogPhotoUploadResponse] }>()
const { t } = useI18n()
const { message, capture } = useApiError()
const uploading = ref(false)

async function onFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  uploading.value = true
  try {
    const res = await dietLogApi.uploadPhoto(file)
    emit('uploaded', res)
  } catch (e) {
    capture(e)
  } finally {
    uploading.value = false
    input.value = ''
  }
}
</script>

<template>
  <div>
    <input type="file" accept="image/*" :disabled="uploading" data-testid="photo-input" @change="onFile" />
    <p v-if="uploading" class="text-sm text-gray-500 dark:text-gray-400">{{ t('common.loading') }}</p>
    <p v-if="message" class="text-sm text-red-600 dark:text-red-400">{{ message }}</p>
  </div>
</template>
