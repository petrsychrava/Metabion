<script setup lang="ts">
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
</script>

<template>
  <main class="mx-auto max-w-lg p-8 text-center">
    <h1 class="text-2xl font-semibold">{{ t('staffNotice.title') }}</h1>
    <p class="mt-2 text-gray-600 dark:text-gray-400">{{ t('staffNotice.body') }}</p>
    <a href="/app" class="mt-4 inline-block rounded bg-blue-600 px-4 py-2 text-white">{{ t('staffNotice.openStaffApp') }}</a>
  </main>
</template>
