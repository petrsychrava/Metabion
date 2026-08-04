import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import { accountApi } from '@/api/account'
import { ApiError, resetCsrfToken } from '@/api/http'
import { useRedFlagsStore } from '@/stores/redFlags'
import { setLocale } from '@/i18n'
import { setTheme } from '@/theme'
import type { LoginResponse } from '@/types/api'

export type AuthStatus = 'unknown' | 'authenticated' | 'anonymous'

export const useAuthStore = defineStore('auth', () => {
  const email = ref<string | null>(null)
  const roles = ref<string[]>([])
  const status = ref<AuthStatus>('unknown')
  const mfaRequired = ref(false)

  const isAuthenticated = computed(() => status.value === 'authenticated')
  const isPatient = computed(() => roles.value.includes('PATIENT'))

  /** Best-effort sync of the persisted language preference; failures never break auth flows. */
  async function syncLanguagePreference(): Promise<void> {
    try {
      const pref = await accountApi.getLanguagePreference()
      setLocale(pref.language === 'CS' ? 'cs' : 'en')
    } catch {
      // Keep the current locale when the preference cannot be fetched.
    }
  }

  /** Best-effort sync of the persisted theme preference; failures never break auth flows. */
  async function syncThemePreference(): Promise<void> {
    try {
      const pref = await accountApi.getThemePreference()
      setTheme(pref.theme)
    } catch {
      // Keep the current theme when the preference cannot be fetched.
    }
  }

  async function fetchMe(): Promise<void> {
    try {
      const me = await authApi.me()
      email.value = me.email
      roles.value = me.roles
      status.value = 'authenticated'
      await syncLanguagePreference()
      await syncThemePreference()
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        email.value = null
        roles.value = []
        status.value = 'anonymous'
        return
      }
      throw e
    }
  }

  async function login(emailInput: string, password: string): Promise<LoginResponse> {
    const res = await authApi.login(emailInput, password)
    if (res.status === 'MFA_REQUIRED') {
      mfaRequired.value = true
      return res
    }
    resetCsrfToken()
    mfaRequired.value = false
    email.value = res.email
    roles.value = res.roles
    status.value = 'authenticated'
    await syncLanguagePreference()
    await syncThemePreference()
    return res
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      expire()
    }
  }

  /** Local-only reset, e.g. when a request fails with 401 mid-session. */
  function expire(): void {
    resetCsrfToken()
    useRedFlagsStore().clear()
    email.value = null
    roles.value = []
    status.value = 'anonymous'
    mfaRequired.value = false
  }

  return { email, roles, status, mfaRequired, isAuthenticated, isPatient, fetchMe, login, logout, expire }
})
