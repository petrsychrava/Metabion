import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import { ApiError, resetCsrfToken } from '@/api/http'
import type { LoginResponse } from '@/types/api'

export type AuthStatus = 'unknown' | 'authenticated' | 'anonymous'

export const useAuthStore = defineStore('auth', () => {
  const email = ref<string | null>(null)
  const roles = ref<string[]>([])
  const status = ref<AuthStatus>('unknown')
  const mfaRequired = ref(false)

  const isAuthenticated = computed(() => status.value === 'authenticated')
  const isPatient = computed(() => roles.value.includes('PATIENT'))

  async function fetchMe(): Promise<void> {
    try {
      const me = await authApi.me()
      email.value = me.email
      roles.value = me.roles
      status.value = 'authenticated'
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
    email.value = null
    roles.value = []
    status.value = 'anonymous'
    mfaRequired.value = false
  }

  return { email, roles, status, mfaRequired, isAuthenticated, isPatient, fetchMe, login, logout, expire }
})
