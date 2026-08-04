import { apiFetch } from './http'
import type {
  ClinicalDailyCheckInDetail,
  ClinicalDailyCheckInSummary,
  ClinicalPatientOverview,
  ClinicalRedFlagHistoryPage,
  ClinicalRedFlagSnapshot,
  DailyTrendResponse,
  LabResultSetRequest,
  LabResultSetResponse,
  LabTrendResponse,
  OnboardingReviewRequest,
  OnboardingReviewStatus,
  OnboardingSubmissionResponse,
  OnboardingSubmissionSummary,
  RedFlagHistoryParams,
} from '@/types/api'

export const clinicalApi = {
  overview: () => apiFetch<ClinicalPatientOverview[]>('/api/clinical/overview'),

  listDailyCheckIns: (patientProfileId: number, from: string, to: string) =>
    apiFetch<ClinicalDailyCheckInSummary[]>(
      `/api/clinical/daily-check-ins?patientProfileId=${patientProfileId}&from=${from}&to=${to}`,
    ),
  getDailyCheckIn: (patientProfileId: number, date: string) =>
    apiFetch<ClinicalDailyCheckInDetail>(`/api/clinical/daily-check-ins/${patientProfileId}/${date}`),

  dailyTrend: (patientProfileId: number, from: string, to: string) =>
    apiFetch<DailyTrendResponse>(
      `/api/clinical/trends/daily?patientProfileId=${patientProfileId}&from=${from}&to=${to}`,
    ),

  listLabResultSets: (patientProfileId: number, from: string, to: string) =>
    apiFetch<LabResultSetResponse[]>(
      `/api/clinical/patients/${patientProfileId}/labs/result-sets?from=${from}&to=${to}`,
    ),
  getLabResultSet: (patientProfileId: number, id: number) =>
    apiFetch<LabResultSetResponse>(`/api/clinical/patients/${patientProfileId}/labs/result-sets/${id}`),
  createLabResultSet: (patientProfileId: number, req: LabResultSetRequest) =>
    apiFetch<LabResultSetResponse>(`/api/clinical/patients/${patientProfileId}/labs/result-sets`, {
      method: 'POST',
      body: req,
    }),
  updateLabResultSet: (patientProfileId: number, id: number, req: LabResultSetRequest) =>
    apiFetch<LabResultSetResponse>(`/api/clinical/patients/${patientProfileId}/labs/result-sets/${id}`, {
      method: 'PUT',
      body: req,
    }),
  requestLabRemoval: (patientProfileId: number, id: number, version: number, reason: string) =>
    apiFetch<{ status: string }>(`/api/clinical/patients/${patientProfileId}/labs/result-sets/${id}/removal`, {
      method: 'POST',
      body: { resultSetId: id, version, reason },
    }),
  labTrend: (patientProfileId: number, testCode: string, from: string, to: string) =>
    apiFetch<LabTrendResponse>(
      `/api/clinical/patients/${patientProfileId}/labs/trends/${encodeURIComponent(testCode)}?from=${from}&to=${to}`,
    ),

  currentRedFlags: (patientProfileId: number) =>
    apiFetch<ClinicalRedFlagSnapshot>(`/api/clinical/patients/${patientProfileId}/red-flags/current`),
  redFlagHistory: (patientProfileId: number, params: RedFlagHistoryParams) => {
    const query = new URLSearchParams()
    if (params.from) query.set('from', params.from)
    if (params.to) query.set('to', params.to)
    if (params.severity) query.set('severity', params.severity)
    if (params.cursor) query.set('cursor', params.cursor)
    if (params.size) query.set('size', String(params.size))
    const qs = query.toString()
    return apiFetch<ClinicalRedFlagHistoryPage>(
      `/api/clinical/patients/${patientProfileId}/red-flags/history${qs ? `?${qs}` : ''}`,
    )
  },

  listOnboardingSubmissions: (context?: string, status?: OnboardingReviewStatus) => {
    const query = new URLSearchParams()
    if (context) query.set('context', context)
    if (status) query.set('status', status)
    const qs = query.toString()
    return apiFetch<OnboardingSubmissionSummary[]>(`/api/clinical/onboarding/submissions${qs ? `?${qs}` : ''}`)
  },
  getOnboardingSubmission: (id: number) =>
    apiFetch<OnboardingSubmissionResponse>(`/api/clinical/onboarding/submissions/${id}`),
  reviewOnboardingSubmission: (id: number, req: OnboardingReviewRequest) =>
    apiFetch<OnboardingSubmissionResponse>(`/api/clinical/onboarding/submissions/${id}/review`, {
      method: 'POST',
      body: req,
    }),
}
