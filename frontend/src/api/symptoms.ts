import { apiFetch } from './http'
import type {
  DailyTrendResponse,
  SymptomCheckInRequest,
  SymptomCheckInResponse,
  SymptomQuestionnaire,
} from '@/types/api'

export const symptomApi = {
  activeQuestionnaire: () => apiFetch<SymptomQuestionnaire>('/api/symptom-questionnaires/active'),
  saveCheckIn: (req: SymptomCheckInRequest) =>
    apiFetch<SymptomCheckInResponse>('/api/symptom-check-ins', { method: 'POST', body: req }),
  getCheckIn: (date: string) => apiFetch<SymptomCheckInResponse>(`/api/symptom-check-ins/${date}`),
  listCheckIns: (from: string, to: string) =>
    apiFetch<SymptomCheckInResponse[]>(`/api/symptom-check-ins?from=${from}&to=${to}`),
  dailyTrend: (from: string, to: string) =>
    apiFetch<DailyTrendResponse>(`/api/trends/daily?from=${from}&to=${to}`),
}
