<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { accountApi } from '@/api/account'
import { useApiError } from '@/composables/useApiError'
import FieldError from '@/components/FieldError.vue'
import type { Sex } from '@/types/api'

const { t } = useI18n()
const { message, fieldErrors, capture } = useApiError()
const dateOfBirth = ref('')
const sex = ref<Sex>('PREFER_NOT_TO_SAY')
const countryRegion = ref('')
const timezone = ref('')
const saved = ref(false)
const loading = ref(true)

const sexOptions: Sex[] = ['FEMALE', 'MALE', 'INTERSEX', 'PREFER_NOT_TO_SAY']

onMounted(async () => {
  try {
    const p = await accountApi.getProfile()
    dateOfBirth.value = p.dateOfBirth
    sex.value = p.sex
    countryRegion.value = p.countryRegion
    timezone.value = p.timezone
  } finally {
    loading.value = false
  }
})

async function submit() {
  saved.value = false
  try {
    await accountApi.updateProfile({
      dateOfBirth: dateOfBirth.value,
      sex: sex.value,
      countryRegion: countryRegion.value,
      timezone: timezone.value,
    })
    saved.value = true
  } catch (e) {
    capture(e)
  }
}
</script>

<template>
  <section class="max-w-md">
    <h1 class="text-2xl font-semibold">{{ t('account.profileTitle') }}</h1>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <form v-else class="mt-4 space-y-4" @submit.prevent="submit">
      <p v-if="message" class="rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
      <p v-if="saved" class="rounded bg-green-50 p-3 text-sm text-green-700">{{ t('common.saved') }}</p>
      <div>
        <label class="block text-sm font-medium" for="dob">{{ t('account.dateOfBirth') }}</label>
        <input id="dob" v-model="dateOfBirth" type="date" required
               class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        <FieldError :message="fieldErrors.dateOfBirth" />
      </div>
      <div>
        <label class="block text-sm font-medium" for="sex">{{ t('account.sex') }}</label>
        <select id="sex" v-model="sex" class="mt-1 w-full rounded border border-gray-300 px-3 py-2">
          <option v-for="s in sexOptions" :key="s" :value="s">{{ t(`sex.${s}`) }}</option>
        </select>
        <FieldError :message="fieldErrors.sex" />
      </div>
      <div>
        <label class="block text-sm font-medium" for="country">{{ t('account.countryRegion') }}</label>
        <input id="country" v-model="countryRegion" type="text" required maxlength="100"
               class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        <FieldError :message="fieldErrors.countryRegion" />
      </div>
      <div>
        <label class="block text-sm font-medium" for="tz">{{ t('account.timezone') }}</label>
        <input id="tz" v-model="timezone" type="text" required maxlength="100" placeholder="Europe/Prague"
               class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        <FieldError :message="fieldErrors.timezone" />
      </div>
      <button type="submit" class="rounded bg-blue-600 px-4 py-2 text-white">{{ t('common.save') }}</button>
    </form>
    <p class="mt-6 text-sm">
      <router-link to="/account/access-tokens" class="text-blue-600">{{ t('account.tokensTitle') }}</router-link>
    </p>
  </section>
</template>
