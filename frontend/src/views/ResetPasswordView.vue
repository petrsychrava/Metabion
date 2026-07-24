<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { authApi } from '@/api/auth'
import { useApiError } from '@/composables/useApiError'
import FieldError from '@/components/FieldError.vue'

const { t } = useI18n()
const route = useRoute()
const { message, fieldErrors, capture } = useApiError()
const newPassword = ref('')
const done = ref(false)
const submitting = ref(false)

async function submit() {
  submitting.value = true
  try {
    const token = typeof route.query.token === 'string' ? route.query.token : ''
    await authApi.resetPassword(token, newPassword.value)
    done.value = true
  } catch (e) {
    capture(e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="mx-auto max-w-sm p-8">
    <h1 class="text-2xl font-semibold">{{ t('auth.resetPassword') }}</h1>
    <p v-if="done" class="mt-6 rounded bg-green-50 p-3 text-sm text-green-700">{{ t('auth.passwordReset') }}</p>
    <form v-else class="mt-6 space-y-4" @submit.prevent="submit">
      <p v-if="message" class="rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
      <div>
        <label class="block text-sm font-medium" for="newPassword">{{ t('auth.newPassword') }}</label>
        <input id="newPassword" v-model="newPassword" type="password" required minlength="12" maxlength="72"
               autocomplete="new-password" class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        <FieldError :message="fieldErrors.newPassword" />
      </div>
      <button type="submit" :disabled="submitting"
              class="w-full rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50">
        {{ t('auth.resetPassword') }}
      </button>
    </form>
  </main>
</template>
