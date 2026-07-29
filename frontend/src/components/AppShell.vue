<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { setLocale, type AppLocale } from '@/i18n'
import { setTheme, currentTheme, type ThemePreference } from '@/theme'
import { accountApi } from '@/api/account'

const { t, locale } = useI18n()
const auth = useAuthStore()
const router = useRouter()

const links = computed(() => [
  { to: '/', label: t('nav.dashboard') },
  { to: '/diet-logs', label: t('nav.dietLogs') },
  { to: '/check-ins', label: t('nav.checkIns') },
  { to: '/trends', label: t('nav.trends') },
  { to: '/labs', label: t('nav.labs') },
  { to: '/onboarding', label: t('nav.onboarding') },
  { to: '/education', label: t('nav.education') },
  { to: '/account', label: t('nav.account') },
])

async function switchLocale(event: Event) {
  const next = (event.target as HTMLSelectElement).value as AppLocale
  setLocale(next)
  try {
    await accountApi.updateLanguagePreference(next === 'cs' ? 'CS' : 'EN')
  } catch {
    // Preference persistence is best-effort; the local choice still applies.
  }
}

const theme = ref<ThemePreference>(currentTheme())

async function switchTheme() {
  setTheme(theme.value)
  try {
    await accountApi.updateThemePreference(theme.value)
  } catch {
    // Preference persistence is best-effort; the local choice still applies.
  }
}

async function logout() {
  await auth.logout()
  await router.push('/login')
}
</script>

<template>
  <div class="min-h-screen bg-gray-50 dark:bg-gray-900">
    <header class="border-b bg-white dark:border-gray-700 dark:bg-gray-800">
      <div class="mx-auto flex max-w-5xl items-center gap-4 px-4 py-3">
        <span class="text-lg font-semibold">{{ t('app.title') }}</span>
        <nav class="flex flex-1 flex-wrap gap-3 text-sm">
          <router-link v-for="link in links" :key="link.to" :to="link.to"
                       class="text-gray-700 hover:text-blue-700 dark:text-gray-300 dark:hover:text-blue-300"
                       :active-class="link.to === '/' ? '' : 'font-semibold text-blue-700 dark:text-blue-300'"
                       exact-active-class="font-semibold text-blue-700 dark:text-blue-300">
            {{ link.label }}
          </router-link>
        </nav>
        <select :value="locale" class="rounded border border-gray-300 px-2 py-1 text-sm dark:border-gray-600 dark:bg-gray-800" @change="switchLocale">
          <option value="en">EN</option>
          <option value="cs">CS</option>
        </select>
        <select v-model="theme" :aria-label="t('theme.label')"
                class="rounded border border-gray-300 px-2 py-1 text-sm dark:border-gray-600 dark:bg-gray-800"
                @change="switchTheme">
          <option value="SYSTEM">{{ t('theme.system') }}</option>
          <option value="LIGHT">{{ t('theme.light') }}</option>
          <option value="DARK">{{ t('theme.dark') }}</option>
        </select>
        <button class="text-sm text-gray-700 hover:text-blue-700 dark:text-gray-300 dark:hover:text-blue-300" @click="logout">{{ t('nav.logout') }}</button>
      </div>
    </header>
    <main class="mx-auto max-w-5xl px-4 py-6">
      <router-view />
    </main>
  </div>
</template>
