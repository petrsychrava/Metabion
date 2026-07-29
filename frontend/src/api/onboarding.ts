import { apiFetch } from './http'
import type {
  OnboardingSubmissionRequest,
  OnboardingSubmissionResponse,
  OnboardingSubmissionSummary,
} from '@/types/api'

export const onboardingApi = {
  submit: (req: OnboardingSubmissionRequest) =>
    apiFetch<OnboardingSubmissionResponse>('/api/onboarding/submissions', { method: 'POST', body: req }),
  latest: () => apiFetch<OnboardingSubmissionResponse>('/api/onboarding/submissions/latest'),
  history: () => apiFetch<OnboardingSubmissionSummary[]>('/api/onboarding/submissions'),
  get: (id: number) => apiFetch<OnboardingSubmissionResponse>(`/api/onboarding/submissions/${id}`),
}
