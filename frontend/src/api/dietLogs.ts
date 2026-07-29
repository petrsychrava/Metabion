import { apiFetch } from './http'
import type {
  DailyDietLogRequest,
  DailyDietLogResponse,
  DailyDietLogSummary,
  DailyMeasurementEntryRequest,
  DailyMeasurementEntryResponse,
  DietLogPhotoUploadResponse,
} from '@/types/api'

export const dietLogApi = {
  save: (req: DailyDietLogRequest) =>
    apiFetch<DailyDietLogResponse>('/api/diet-logs', { method: 'POST', body: req }),
  get: (date: string) => apiFetch<DailyDietLogResponse>(`/api/diet-logs/${date}`),
  list: (from: string, to: string) =>
    apiFetch<DailyDietLogSummary[]>(`/api/diet-logs?from=${from}&to=${to}`),
  addMeasurement: (date: string, entry: DailyMeasurementEntryRequest) =>
    apiFetch<DailyMeasurementEntryResponse>(`/api/diet-logs/${date}/measurements`, { method: 'POST', body: entry }),
  uploadPhoto: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return apiFetch<DietLogPhotoUploadResponse>('/api/diet-log-photos/uploads', { method: 'POST', formData })
  },
  photoContentUrl: (id: number) => `/api/diet-log-photos/${id}/content`,
}
