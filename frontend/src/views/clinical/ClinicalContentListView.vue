<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { contentEducationApi } from '@/api/contentEducation'
import { useApiError } from '@/composables/useApiError'
import { formatDateTime } from '@/utils/dateTime'
import type { EducationContentStatus, EducationManagementSummary } from '@/types/api'

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()
const { message, capture, clear } = useApiError()

const items = ref<EducationManagementSummary[]>([])
const loading = ref(true)
const copying = ref(false)
const moduleFilter = ref('')
const statusFilter = ref<EducationContentStatus | ''>('')

const statusOptions: EducationContentStatus[] = ['DRAFT', 'IN_REVIEW', 'APPROVED', 'PUBLISHED', 'ARCHIVED', 'REJECTED']

const visible = computed(() => {
  const needle = moduleFilter.value.trim().toLowerCase()
  return items.value.filter((item) => {
    const matchesModule = needle === ''
      || item.moduleSlug.toLowerCase().includes(needle)
      || item.topic.toLowerCase().includes(needle)
    const matchesStatus = statusFilter.value === '' || item.status === statusFilter.value
    return matchesModule && matchesStatus
  })
})

async function load() {
  clear()
  loading.value = true
  try {
    items.value = await contentEducationApi.listVersions()
  } catch (e) {
    items.value = []
    capture(e)
  } finally {
    loading.value = false
  }
}

function open(item: EducationManagementSummary) {
  void router.push(`/clinical/content/${item.moduleSlug}/${item.version}`)
}

async function copy(item: EducationManagementSummary) {
  // Copies of any version of a module share the maxVersion + 1 draft allocation, so all rows'
  // copy buttons serialize on a single flag; it stays set through the post-copy navigation.
  if (copying.value) return
  copying.value = true
  clear()
  const originPath = route.path
  try {
    const draft = await contentEducationApi.copyVersion(item.moduleSlug, item.version)
    // The handler outlives unmount: only redirect into the draft while the list is still current.
    if (route.path !== originPath) return
    await router.push(`/clinical/content/${draft.moduleSlug}/${draft.version}`)
  } catch (e) {
    capture(e)
  } finally {
    copying.value = false
  }
}

onMounted(load)
</script>

<template>
  <section>
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-semibold">{{ t('clinical.content.title') }}</h1>
      <router-link to="/clinical/content/new" data-testid="new-module"
                   class="rounded bg-blue-600 px-3 py-1 text-sm text-white">
        {{ t('clinical.content.newModule') }}
      </router-link>
    </div>

    <div class="mt-2 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('clinical.content.filterModule') }}
        <input v-model="moduleFilter" data-testid="module-filter" type="text"
               class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <label class="text-sm">{{ t('clinical.content.filterStatus') }}
        <select v-model="statusFilter" data-testid="status-filter"
                class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
          <option value="">{{ t('clinical.content.allStatuses') }}</option>
          <option v-for="s in statusOptions" :key="s" :value="s">{{ t(`clinical.content.status.${s}`) }}</option>
        </select>
      </label>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <p v-else-if="visible.length === 0" class="mt-4 text-sm text-gray-600 dark:text-gray-400">{{ t('clinical.content.empty') }}</p>
    <table v-else class="mt-4 w-full border-collapse bg-white text-sm dark:bg-gray-800">
      <thead>
        <tr class="border-b text-left">
          <th class="p-2">{{ t('clinical.content.colModule') }}</th>
          <th class="p-2">{{ t('clinical.content.colVersion') }}</th>
          <th class="p-2">{{ t('clinical.content.colTitle') }}</th>
          <th class="p-2">{{ t('clinical.content.colStatus') }}</th>
          <th class="p-2">{{ t('clinical.content.colAuthor') }}</th>
          <th class="p-2">{{ t('clinical.content.colCreated') }}</th>
          <th class="p-2"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in visible" :key="`${item.moduleSlug}-${item.version}`" data-testid="content-row"
            class="cursor-pointer border-b hover:bg-gray-50 dark:hover:bg-gray-700" @click="open(item)">
          <td class="p-2">{{ item.topic }} <span class="text-gray-500">({{ item.moduleSlug }})</span></td>
          <td class="p-2">{{ item.version }}</td>
          <td class="p-2">{{ item.title ?? t('clinical.noValue') }}</td>
          <td class="p-2">{{ t(`clinical.content.status.${item.status}`) }}</td>
          <td class="p-2">{{ item.authorEmail ?? t('clinical.noValue') }}</td>
          <td class="p-2">{{ formatDateTime(item.createdAt, locale) }}</td>
          <td class="p-2">
            <button data-testid="copy-version" class="rounded border px-2 py-0.5 text-xs" :disabled="copying"
                    @click.stop="copy(item)">
              {{ t('clinical.content.newVersion') }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
