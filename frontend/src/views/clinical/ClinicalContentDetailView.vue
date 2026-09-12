<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import FieldError from '@/components/FieldError.vue'
import { contentEducationApi } from '@/api/contentEducation'
import { useApiError } from '@/composables/useApiError'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/dateTime'
import type { EducationManagementDetail } from '@/types/api'

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { message, fieldErrors, capture, clear } = useApiError()

const moduleSlug = computed(() => route.params.moduleSlug as string)
const version = computed(() => Number(route.params.version))

const detail = ref<EducationManagementDetail | null>(null)
const loading = ref(true)
const reviewOpen = ref(false)
const notes = ref('')
const openLesson = ref<string | null>(null)
const transitioning = ref(false)

const isAuthor = computed(() => !!detail.value?.authorEmail && detail.value.authorEmail === auth.email)
const isAdmin = computed(() => auth.roles.includes('ADMIN'))
const canSubmitReview = computed(() => detail.value?.status === 'DRAFT' || detail.value?.status === 'REJECTED')
const editable = computed(() => detail.value?.status === 'DRAFT' || detail.value?.status === 'REJECTED')
const canReview = computed(() => detail.value?.status === 'IN_REVIEW' && (!isAuthor.value || isAdmin.value))
const canPublish = computed(() => detail.value?.status === 'APPROVED')
// Mirrors the server-side validatePublishable invariants: an English module localization always
// exists (enforced at create), so lessons drive the check; title is non-null exactly when the
// lesson has an English localization.
const publishable = computed(() => !!detail.value
  && detail.value.lessons.length > 0
  && detail.value.lessons.every((lesson) => !!lesson.title))

let loadSeq = 0

async function load() {
  // Clear first so a failed reload never leaves the previous version rendered under a new URL.
  // The sequence guard keeps an older, slower response from clobbering a newer load: only the
  // latest load touches detail/loading, and only its failure surfaces.
  const seq = ++loadSeq
  const slug = moduleSlug.value
  const ver = version.value
  detail.value = null
  clear()
  loading.value = true
  try {
    const data = await contentEducationApi.getVersion(slug, ver)
    if (seq !== loadSeq) return
    detail.value = data
    openLesson.value ??= data.lessons[0]?.lessonSlug ?? null
  } catch (e) {
    if (seq === loadSeq) capture(e)
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

async function transition(call: () => Promise<EducationManagementDetail>) {
  // Serialize lifecycle decisions: rapid clicks must not issue concurrent POSTs whose commit
  // order would decide the final status. The flag stays set through the error-path resync.
  if (transitioning.value) return
  transitioning.value = true
  clear()
  const slug = moduleSlug.value
  const ver = version.value
  try {
    const data = await call()
    // Navigation supersedes the mutation: the watcher's load governs the page; assigning here
    // would render the old version under the new URL.
    if (slug !== moduleSlug.value || ver !== version.value) return
    detail.value = data
    reviewOpen.value = false
    notes.value = ''
  } catch (e) {
    // Superseded failures belong to the page the user left; the new route's in-flight load
    // governs state and must not be clobbered by this error.
    if (slug !== moduleSlug.value || ver !== version.value) return
    // Another manager may have changed the state; resync the action bar instead of going stale,
    // then surface the error (load() clears any previous message first).
    await load()
    capture(e)
  } finally {
    transitioning.value = false
  }
}

async function copy() {
  // Shares `transitioning` with the lifecycle buttons so both mutation families serialize in
  // either direction; the backend allocates drafts via maxVersion + 1, so concurrent copies
  // would race the unique module/version constraint. The flag stays set through the error-path
  // resync and the post-copy navigation.
  if (transitioning.value) return
  transitioning.value = true
  clear()
  try {
    const draft = await contentEducationApi.copyVersion(moduleSlug.value, version.value)
    await router.push(`/clinical/content/${draft.moduleSlug}/${draft.version}`)
  } catch (e) {
    await load()
    capture(e)
  } finally {
    transitioning.value = false
  }
}

watch(() => [route.params.moduleSlug, route.params.version], () => {
  openLesson.value = null
  void load()
})

onMounted(load)
</script>

<template>
  <section class="max-w-3xl">
    <router-link to="/clinical/content" class="text-sm text-blue-600 dark:text-blue-400">
      ← {{ t('clinical.content.backToList') }}
    </router-link>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <template v-if="detail">
      <div class="mt-2 flex flex-wrap items-center gap-3">
        <h1 class="text-2xl font-semibold">
          {{ t('clinical.content.detailTitle') }} {{ detail.moduleSlug }} v{{ detail.version }}
        </h1>
        <span class="rounded bg-gray-100 px-2 py-0.5 text-xs dark:bg-gray-700"
              data-testid="status-badge">{{ t(`clinical.content.status.${detail.status}`) }}</span>
        <span v-if="detail.reviewBypassed" class="rounded bg-yellow-100 px-2 py-0.5 text-xs text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200">
          {{ t('clinical.content.reviewBypassed') }}
        </span>
      </div>
      <p class="mt-1 text-gray-600 dark:text-gray-400">{{ detail.topic }}</p>

      <div class="mt-2 rounded border bg-white p-3 text-sm dark:bg-gray-800" data-testid="module-english">
        <p class="font-medium">{{ detail.englishTitle }}</p>
        <p class="mt-1 whitespace-pre-line text-gray-600 dark:text-gray-400">{{ detail.englishSummary }}</p>
      </div>
      <div v-if="detail.czechTitle !== null" class="mt-2 rounded border bg-white p-3 text-sm dark:bg-gray-800"
           data-testid="module-czech">
        <p class="font-medium">
          <span class="text-gray-500">{{ t('clinical.content.czechLabel') }}:</span> {{ detail.czechTitle }}
        </p>
        <p class="mt-1 whitespace-pre-line text-gray-600 dark:text-gray-400">{{ detail.czechSummary }}</p>
      </div>

      <dl class="mt-4 grid grid-cols-2 gap-x-6 gap-y-1 text-sm sm:grid-cols-3">
        <div>
          <dt class="text-gray-500">{{ t('clinical.content.meta.author') }}</dt>
          <dd data-testid="meta-author">{{ detail.authorEmail ?? t('clinical.noValue') }}</dd>
        </div>
        <div>
          <dt class="text-gray-500">{{ t('clinical.content.meta.createdAt') }}</dt>
          <dd>{{ formatDateTime(detail.createdAt, locale) }}</dd>
        </div>
        <div v-if="detail.submittedAt">
          <dt class="text-gray-500">{{ t('clinical.content.meta.submittedAt') }}</dt>
          <dd>{{ formatDateTime(detail.submittedAt, locale) }}</dd>
        </div>
        <div v-if="detail.reviewedByEmail">
          <dt class="text-gray-500">{{ t('clinical.content.meta.reviewedBy') }}</dt>
          <dd>{{ detail.reviewedByEmail }}</dd>
        </div>
        <div v-if="detail.reviewedAt">
          <dt class="text-gray-500">{{ t('clinical.content.meta.reviewedAt') }}</dt>
          <dd>{{ formatDateTime(detail.reviewedAt, locale) }}</dd>
        </div>
        <div v-if="detail.publishedByEmail">
          <dt class="text-gray-500">{{ t('clinical.content.meta.publishedBy') }}</dt>
          <dd>{{ detail.publishedByEmail }}</dd>
        </div>
        <div v-if="detail.publishedAt">
          <dt class="text-gray-500">{{ t('clinical.content.meta.publishedAt') }}</dt>
          <dd>{{ formatDateTime(detail.publishedAt, locale) }}</dd>
        </div>
      </dl>

      <div class="mt-6 flex flex-wrap gap-2">
        <button v-if="canSubmitReview" data-testid="submit-review"
                class="rounded bg-blue-600 px-3 py-1 text-sm text-white disabled:opacity-50"
                :disabled="transitioning"
                @click="transition(() => contentEducationApi.submitReview(moduleSlug, version))">
          {{ t('clinical.content.actions.submitReview') }}
        </button>
        <template v-if="canReview">
          <button data-testid="approve"
                  class="rounded bg-green-600 px-3 py-1 text-sm text-white disabled:opacity-50"
                  :disabled="transitioning"
                  @click="reviewOpen = !reviewOpen">
            {{ t('clinical.content.actions.approve') }}
          </button>
          <button data-testid="reject"
                  class="rounded bg-red-600 px-3 py-1 text-sm text-white disabled:opacity-50"
                  :disabled="transitioning"
                  @click="reviewOpen = !reviewOpen">
            {{ t('clinical.content.actions.reject') }}
          </button>
        </template>
        <button v-if="canPublish" data-testid="publish"
                class="rounded bg-green-700 px-3 py-1 text-sm text-white disabled:opacity-50"
                :disabled="transitioning || !publishable"
                :title="publishable ? undefined : t('clinical.content.notPublishable')"
                @click="transition(() => contentEducationApi.publish(moduleSlug, version))">
          {{ t('clinical.content.actions.publish') }}
        </button>
        <button data-testid="copy" class="rounded border px-3 py-1 text-sm" :disabled="transitioning" @click="copy">
          {{ t('clinical.content.actions.copy') }}
        </button>
        <router-link v-if="editable" :to="`/clinical/content/${moduleSlug}/${version}/edit`"
                     data-testid="edit-content" class="rounded border px-3 py-1 text-sm">
          {{ t('clinical.content.actions.edit') }}
        </router-link>
      </div>

      <div v-if="reviewOpen" class="mt-4 rounded border p-3">
        <label class="text-sm">{{ t('clinical.content.reviewNotes') }}
          <textarea v-model="notes" data-testid="review-notes" rows="3" maxlength="2000"
                    :placeholder="t('clinical.content.notesPlaceholder')"
                    class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
          <FieldError :message="fieldErrors.notes" />
        </label>
        <div class="mt-2 flex gap-2">
          <button data-testid="confirm-approve"
                  class="rounded bg-green-600 px-3 py-1 text-sm text-white disabled:opacity-50"
                  :disabled="transitioning"
                  @click="transition(() => contentEducationApi.review(moduleSlug, version, 'approve', notes))">
            {{ t('clinical.content.actions.approve') }}
          </button>
          <button data-testid="confirm-reject"
                  class="rounded bg-red-600 px-3 py-1 text-sm text-white disabled:opacity-50"
                  :disabled="transitioning"
                  @click="transition(() => contentEducationApi.review(moduleSlug, version, 'reject', notes))">
            {{ t('clinical.content.actions.reject') }}
          </button>
        </div>
      </div>

      <p v-if="detail.reviewNotes" class="mt-4 rounded bg-gray-50 p-3 text-sm dark:bg-gray-800">
        {{ t('clinical.content.reviewNotes') }}: {{ detail.reviewNotes }}
      </p>

      <h2 class="mt-6 font-medium">{{ t('clinical.content.lessonsTitle') }}</h2>
      <div v-if="detail.lessons.length === 0" class="mt-2 text-sm text-gray-600 dark:text-gray-400">
        {{ t('clinical.content.empty') }}
      </div>
      <div v-else class="mt-2 space-y-2">
        <div v-for="lesson in detail.lessons" :key="lesson.lessonSlug" class="rounded border bg-white dark:bg-gray-800">
          <button class="flex w-full items-center justify-between p-4 text-left"
                  @click="openLesson = openLesson === lesson.lessonSlug ? null : lesson.lessonSlug">
            <span>{{ lesson.title ?? lesson.lessonSlug }}</span>
            <span class="text-gray-400">{{ openLesson === lesson.lessonSlug ? '−' : '+' }}</span>
          </button>
          <div v-if="openLesson === lesson.lessonSlug" class="border-t p-4">
            <p v-if="lesson.summary" class="whitespace-pre-line text-gray-600 dark:text-gray-400">{{ lesson.summary }}</p>
            <!-- bodyHtml is server-rendered from staff-authored content; same trust model as the patient view -->
            <div class="prose max-w-none" v-html="lesson.bodyHtml" />
            <div v-if="lesson.czechTitle !== null" class="mt-4 border-t pt-3">
              <p class="text-sm font-medium text-gray-500">{{ t('clinical.content.czechLabel') }}: {{ lesson.czechTitle }}</p>
              <p v-if="lesson.czechSummary !== null"
                 class="mt-1 whitespace-pre-line text-gray-600 dark:text-gray-400">{{ lesson.czechSummary }}</p>
              <div class="prose mt-2 max-w-none" v-html="lesson.czechBodyHtml" />
            </div>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>
