import { apiFetch } from './http'
import type {
  IssuePatientAccessTokenRequest,
  IssuePatientAccessTokenResponse,
  PatientAccessTokenSummary,
  PatientProfile,
} from '@/types/api'

export const accountApi = {
  getProfile: () => apiFetch<PatientProfile>('/api/account/profile'),
  updateProfile: (profile: PatientProfile) =>
    apiFetch<{ status: string }>('/api/account/profile', { method: 'PUT', body: profile }),
  getLanguagePreference: () => apiFetch<{ language: 'EN' | 'CS' }>('/api/account/preferences/language'),
  updateLanguagePreference: (language: 'EN' | 'CS') =>
    apiFetch<{ status: string }>('/api/account/preferences/language', { method: 'PUT', body: { language } }),
}

export const accessTokenApi = {
  issue: (req: IssuePatientAccessTokenRequest) =>
    apiFetch<IssuePatientAccessTokenResponse>('/api/account/access-tokens', { method: 'POST', body: req }),
  list: () => apiFetch<PatientAccessTokenSummary[]>('/api/account/access-tokens'),
  revoke: (id: number) => apiFetch<void>(`/api/account/access-tokens/${id}`, { method: 'DELETE' }),
}
