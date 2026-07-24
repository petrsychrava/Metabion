<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { accessTokenApi } from '@/api/account'
import { useApiError } from '@/composables/useApiError'
import type { PatientAccessClientType, PatientAccessTokenSummary } from '@/types/api'

const { t } = useI18n()
const { message, capture } = useApiError()

const ALL_SCOPES = [
  'patient:profile:read', 'patient:profile:write',
  'patient:diet-log:read', 'patient:diet-log:write',
  'patient:diet-photo:read', 'patient:diet-photo:write',
  'patient:symptom:read', 'patient:symptom:write',
  'patient:onboarding:read', 'patient:onboarding:write',
  'patient:education:read', 'patient:education:write',
  'patient:lab:read', 'patient:lab:write',
  'patient:trend:read',
]
const CLIENT_TYPES: PatientAccessClientType[] = ['MCP_CLAUDE', 'MCP_CODEX', 'MCP_OTHER', 'MOBILE_IOS', 'MOBILE_ANDROID', 'INTERNAL_TEST']

const tokens = ref<PatientAccessTokenSummary[]>([])
const loading = ref(true)
const displayLabel = ref('')
const clientType = ref<PatientAccessClientType>('MCP_OTHER')
const expiresInDays = ref(30)
const selectedScopes = ref<string[]>(['patient:profile:read'])
const plainToken = ref<string | null>(null)
const copied = ref(false)
const pendingRevoke = ref<PatientAccessTokenSummary | null>(null)

async function load() {
  tokens.value = await accessTokenApi.list()
  loading.value = false
}

onMounted(load)

async function issue() {
  try {
    const res = await accessTokenApi.issue({
      clientType: clientType.value,
      displayLabel: displayLabel.value,
      expiresInDays: expiresInDays.value,
      scopes: selectedScopes.value,
    })
    plainToken.value = res.plainToken
    copied.value = false
    displayLabel.value = ''
    await load()
  } catch (e) {
    capture(e)
  }
}

async function copyToken() {
  if (plainToken.value) {
    await navigator.clipboard.writeText(plainToken.value)
    copied.value = true
  }
}

async function confirmRevoke() {
  if (!pendingRevoke.value) return
  try {
    await accessTokenApi.revoke(pendingRevoke.value.tokenId)
    pendingRevoke.value = null
    await load()
  } catch (e) {
    capture(e)
  }
}

function formatInstant(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString() : t('account.never')
}
</script>

<template>
  <section class="max-w-2xl">
    <h1 class="text-2xl font-semibold">{{ t('account.tokensTitle') }}</h1>
    <p class="mt-1 text-sm text-gray-600">{{ t('account.tokensIntro') }}</p>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>

    <div v-if="plainToken" class="mt-4 rounded border border-amber-400 bg-amber-50 p-4">
      <h2 class="font-medium">{{ t('account.tokenCreatedTitle') }}</h2>
      <p class="mt-1 text-sm text-amber-800">{{ t('account.tokenCreatedWarning') }}</p>
      <code data-testid="plain-token" class="mt-2 block break-all rounded bg-white p-2 text-sm">{{ plainToken }}</code>
      <div class="mt-2 flex gap-2">
        <button class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="copyToken">
          {{ copied ? t('common.copied') : t('common.copy') }}
        </button>
        <button class="rounded border px-3 py-1 text-sm" @click="plainToken = null">{{ t('common.close') }}</button>
      </div>
    </div>

    <form class="mt-6 space-y-3 rounded border bg-white p-4" @submit.prevent="issue">
      <div>
        <label class="block text-sm font-medium" for="label">{{ t('account.displayLabel') }}</label>
        <input id="label" v-model="displayLabel" data-testid="display-label" type="text" required maxlength="120"
               class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="block text-sm font-medium" for="ctype">{{ t('account.clientType') }}</label>
          <select id="ctype" v-model="clientType" class="mt-1 w-full rounded border border-gray-300 px-3 py-2">
            <option v-for="c in CLIENT_TYPES" :key="c" :value="c">{{ c }}</option>
          </select>
        </div>
        <div>
          <label class="block text-sm font-medium" for="days">{{ t('account.expiresInDays') }}</label>
          <input id="days" v-model.number="expiresInDays" type="number" min="1" max="90" required
                 class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        </div>
      </div>
      <fieldset>
        <legend class="text-sm font-medium">{{ t('account.scopes') }}</legend>
        <div class="mt-1 grid grid-cols-2 gap-1 text-sm">
          <label v-for="s in ALL_SCOPES" :key="s" class="flex items-center gap-2">
            <input v-model="selectedScopes" type="checkbox" :value="s" /> {{ s }}
          </label>
        </div>
      </fieldset>
      <button type="submit" class="rounded bg-blue-600 px-4 py-2 text-white">{{ t('account.issue') }}</button>
    </form>

    <p v-if="loading" class="mt-6">{{ t('common.loading') }}</p>
    <p v-else-if="tokens.length === 0" class="mt-6 text-sm text-gray-600">{{ t('account.noTokens') }}</p>
    <ul v-else class="mt-6 space-y-3">
      <li v-for="token in tokens" :key="token.tokenId" class="rounded border bg-white p-4">
        <div class="flex items-start justify-between">
          <div>
            <p class="font-medium">{{ token.displayLabel }} <span class="text-sm text-gray-500">({{ token.clientType }})</span></p>
            <p class="mt-1 text-sm text-gray-600">
              {{ t('account.createdAt') }}: {{ formatInstant(token.createdAt) }} ·
              {{ t('account.expiresAt') }}: {{ formatInstant(token.expiresAt) }} ·
              {{ t('account.lastUsed') }}: {{ formatInstant(token.lastUsedAt) }}
            </p>
            <p class="mt-1 text-xs text-gray-500">{{ token.scopes.join(', ') }}</p>
          </div>
          <button :data-testid="`revoke-${token.tokenId}`" class="text-sm text-red-600"
                  @click="pendingRevoke = token">{{ t('account.revoke') }}</button>
        </div>
      </li>
    </ul>

    <div v-if="pendingRevoke" class="fixed inset-0 flex items-center justify-center bg-black/40">
      <div class="w-full max-w-sm rounded bg-white p-6">
        <p class="text-sm">{{ t('account.revokeConfirm') }}</p>
        <div class="mt-4 flex justify-end gap-2">
          <button class="rounded border px-3 py-1 text-sm" @click="pendingRevoke = null">{{ t('common.cancel') }}</button>
          <button data-testid="confirm-revoke" class="rounded bg-red-600 px-3 py-1 text-sm text-white"
                  @click="confirmRevoke">{{ t('account.confirm') }}</button>
        </div>
      </div>
    </div>
  </section>
</template>
