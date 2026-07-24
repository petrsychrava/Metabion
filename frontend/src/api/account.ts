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
}

export const accessTokenApi = {
  issue: (req: IssuePatientAccessTokenRequest) =>
    apiFetch<IssuePatientAccessTokenResponse>('/api/account/access-tokens', { method: 'POST', body: req }),
  list: () => apiFetch<PatientAccessTokenSummary[]>('/api/account/access-tokens'),
  revoke: (id: number) => apiFetch<void>(`/api/account/access-tokens/${id}`, { method: 'DELETE' }),
}
