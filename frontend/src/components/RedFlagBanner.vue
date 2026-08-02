<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRedFlagsStore } from '@/stores/redFlags'
import { severityBadgeClass } from '@/utils/redFlags'
import type { RedFlagSeverity } from '@/types/api'

const props = defineProps<{ severities: RedFlagSeverity[] }>()

const { t } = useI18n()
const redFlags = useRedFlagsStore()

const visibleSeverity = computed<RedFlagSeverity | null>(() => {
  const highest = redFlags.snapshot?.highestSeverity
  if (!highest || redFlags.loadFailed || !props.severities.includes(highest)) return null
  return highest
})

const bannerClass = computed(() =>
  visibleSeverity.value ? severityBadgeClass(visibleSeverity.value) : '',
)
const severityLabel = computed(() =>
  visibleSeverity.value ? t(`redFlags.severity.${visibleSeverity.value}`) : '',
)
const count = computed(() => redFlags.snapshot?.flags.length ?? 0)
</script>

<template>
  <div v-if="visibleSeverity" data-testid="red-flag-banner"
       class="flex items-center gap-2 rounded p-3 text-sm" :class="bannerClass">
    <span class="font-medium">{{ severityLabel }}</span>
    <span>{{ t('redFlags.bannerCount', { count }) }}</span>
    <router-link to="/red-flags" class="ml-auto underline">{{ t('redFlags.viewDetails') }}</router-link>
  </div>
</template>
