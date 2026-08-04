<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useApiError } from '@/composables/useApiError'

const { t } = useI18n()
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const { message, capture } = useApiError()

const email = ref('')
const password = ref('')
const submitting = ref(false)

async function submit() {
  submitting.value = true
  try {
    const res = await auth.login(email.value, password.value)
    if (res.status === 'MFA_REQUIRED') {
      message.value = t('auth.mfaRequired')
      return
    }
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : null
    await router.push(redirect ?? auth.homePath)
  } catch (e) {
    capture(e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="mx-auto max-w-sm p-8">
    <h1 class="text-2xl font-semibold">{{ t('auth.login') }}</h1>
    <form class="mt-6 space-y-4" @submit.prevent="submit">
      <p v-if="message" class="rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
      <div>
        <label class="block text-sm font-medium" for="email">{{ t('auth.email') }}</label>
        <input id="email" v-model="email" type="email" required autocomplete="email"
               class="mt-1 w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-800" />
      </div>
      <div>
        <label class="block text-sm font-medium" for="password">{{ t('auth.password') }}</label>
        <input id="password" v-model="password" type="password" required autocomplete="current-password"
               class="mt-1 w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-800" />
      </div>
      <button type="submit" :disabled="submitting"
              class="w-full rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50">
        {{ t('auth.login') }}
      </button>
    </form>
    <div class="mt-4 flex justify-between text-sm">
      <router-link to="/forgot-password" class="text-blue-600 dark:text-blue-400">{{ t('auth.forgotPassword') }}</router-link>
      <router-link to="/register" class="text-blue-600 dark:text-blue-400">{{ t('auth.register') }}</router-link>
    </div>
  </main>
</template>
