<script setup lang="ts">
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
</script>

<template>
  <main class="mx-auto max-w-lg p-8 text-center">
    <h1 class="text-2xl font-semibold">404</h1>
    <p class="mt-2 text-gray-600 dark:text-gray-400">{{ t('errors.not_found') }}</p>
  </main>
</template>
