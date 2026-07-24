<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { authApi } from '@/api/auth'
import { useApiError } from '@/composables/useApiError'

const { t } = useI18n()
const { message, capture } = useApiError()
const email = ref('')
const done = ref(false)
const submitting = ref(false)

async function submit() {
  submitting.value = true
  try {
    await authApi.forgotPassword(email.value)
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
    <h1 class="text-2xl font-semibold">{{ t('auth.forgotPassword') }}</h1>
    <p v-if="done" class="mt-6 rounded bg-green-50 p-3 text-sm text-green-700">{{ t('auth.resetLinkSent') }}</p>
    <form v-else class="mt-6 space-y-4" @submit.prevent="submit">
      <p v-if="message" class="rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
      <div>
        <label class="block text-sm font-medium" for="email">{{ t('auth.email') }}</label>
        <input id="email" v-model="email" type="email" required autocomplete="email"
               class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
      </div>
      <button type="submit" :disabled="submitting"
              class="w-full rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50">
        {{ t('auth.sendResetLink') }}
      </button>
    </form>
  </main>
</template>
