<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { authApi } from '@/api/auth'
import { useApiError } from '@/composables/useApiError'

const { t } = useI18n()
const route = useRoute()
const { message, capture } = useApiError()
const verified = ref(false)
const loading = ref(true)

onMounted(async () => {
  try {
    const token = typeof route.query.token === 'string' ? route.query.token : ''
    await authApi.verify(token)
    verified.value = true
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <main class="mx-auto max-w-sm p-8">
    <p v-if="loading">{{ t('common.loading') }}</p>
    <template v-else>
      <p v-if="verified" class="rounded bg-green-50 p-3 text-sm text-green-700">{{ t('auth.verified') }}</p>
      <p v-else class="rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
      <router-link to="/login" class="mt-4 inline-block text-blue-600">{{ t('auth.login') }}</router-link>
    </template>
  </main>
</template>
