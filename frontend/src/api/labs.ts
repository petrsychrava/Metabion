import { apiFetch } from './http'
import type {
  LabResultSetRequest,
  LabResultSetResponse,
  LabTestDefinition,
  LabTrendResponse,
} from '@/types/api'

export const labApi = {
  listTests: () => apiFetch<LabTestDefinition[]>('/api/lab-tests'),
  createResultSet: (req: LabResultSetRequest) =>
    apiFetch<LabResultSetResponse>('/api/lab-result-sets', { method: 'POST', body: req }),
  getResultSet: (id: number) => apiFetch<LabResultSetResponse>(`/api/lab-result-sets/${id}`),
  listResultSets: (from: string, to: string) =>
    apiFetch<LabResultSetResponse[]>(`/api/lab-result-sets?from=${from}&to=${to}`),
  updateResultSet: (id: number, req: LabResultSetRequest) =>
    apiFetch<LabResultSetResponse>(`/api/lab-result-sets/${id}`, { method: 'PUT', body: req }),
  requestRemoval: (id: number, version: number, reason: string) =>
    apiFetch<{ status: string }>(`/api/lab-result-sets/${id}/removal`, {
      method: 'POST',
      body: { resultSetId: id, version, reason },
    }),
  trend: (testCode: string, from: string, to: string) =>
    apiFetch<LabTrendResponse>(`/api/lab-trends/${encodeURIComponent(testCode)}?from=${from}&to=${to}`),
}
