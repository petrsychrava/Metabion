import { apiFetch } from './http'
import type { LoginResponse, MeResponse } from '@/types/api'

export const authApi = {
  register: (email: string, password: string) =>
    apiFetch<{ status: string }>('/api/auth/register', { method: 'POST', body: { email, password }, csrf: false }),

  verify: (token: string) =>
    apiFetch<void>(`/api/auth/verify?token=${encodeURIComponent(token)}`),

  forgotPassword: (email: string) =>
    apiFetch<{ status: string }>('/api/auth/forgot-password', { method: 'POST', body: { email }, csrf: false }),

  resetPassword: (token: string, newPassword: string) =>
    apiFetch<{ status: string }>('/api/auth/reset-password', { method: 'POST', body: { token, newPassword }, csrf: false }),

  login: (email: string, password: string) =>
    apiFetch<LoginResponse>('/api/auth/login', { method: 'POST', body: { email, password }, csrf: false }),

  logout: () => apiFetch<void>('/api/auth/logout', { method: 'POST' }),

  me: () => apiFetch<MeResponse>('/api/auth/me'),
}
