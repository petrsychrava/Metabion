import { apiFetch } from './http'
import type {
  PatientRedFlagHistoryPage,
  PatientRedFlagSnapshot,
  RedFlagHistoryParams,
} from '@/types/api'

export const redFlagApi = {
  getCurrent: () => apiFetch<PatientRedFlagSnapshot>('/api/red-flags/current'),
  getHistory: (params: RedFlagHistoryParams) => {
    const query = new URLSearchParams()
    if (params.from) query.set('from', params.from)
    if (params.to) query.set('to', params.to)
    if (params.severity) query.set('severity', params.severity)
    if (params.cursor) query.set('cursor', params.cursor)
    if (params.size) query.set('size', String(params.size))
    const qs = query.toString()
    return apiFetch<PatientRedFlagHistoryPage>(`/api/red-flags/history${qs ? `?${qs}` : ''}`)
  },
}
