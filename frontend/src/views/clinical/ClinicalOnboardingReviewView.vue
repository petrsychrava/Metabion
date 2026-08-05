<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import ClinicalOnboardingReviewPanel from '@/views/clinical/ClinicalOnboardingReviewPanel.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const submissionId = Number(route.params.submissionId)

async function backToQueue() {
  await router.push('/clinical/onboarding')
}
</script>

<template>
  <section>
    <router-link to="/clinical/onboarding" class="text-sm text-blue-600 dark:text-blue-400">← {{ t('clinical.backToQueue') }}</router-link>
    <h1 class="mb-4 mt-2 text-2xl font-semibold">{{ t('clinical.reviewTitle') }}</h1>
    <ClinicalOnboardingReviewPanel :submission-id="submissionId" @reviewed="backToQueue" />
  </section>
</template>
