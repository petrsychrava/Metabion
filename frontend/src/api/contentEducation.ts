import { apiFetch } from './http'
import type {
  EducationContentFormData,
  EducationManagementDetail,
  EducationManagementSummary,
  EducationModuleCreateRequest,
  MarkdownPreviewResponse,
} from '@/types/api'

function versionPath(moduleSlug: string, version: number): string {
  return `/api/content/education/modules/${encodeURIComponent(moduleSlug)}/versions/${version}`
}

export const contentEducationApi = {
  listVersions: () => apiFetch<EducationManagementSummary[]>('/api/content/education/modules'),

  createModule: (request: EducationModuleCreateRequest) =>
    apiFetch<EducationManagementDetail>('/api/content/education/modules', { method: 'POST', body: request }),

  getVersion: (moduleSlug: string, version: number) =>
    apiFetch<EducationManagementDetail>(versionPath(moduleSlug, version)),

  getVersionForm: (moduleSlug: string, version: number) =>
    apiFetch<EducationContentFormData>(`${versionPath(moduleSlug, version)}/form`),

  updateVersion: (moduleSlug: string, version: number, form: EducationContentFormData) =>
    apiFetch<EducationManagementDetail>(versionPath(moduleSlug, version), { method: 'PUT', body: form }),

  submitReview: (moduleSlug: string, version: number) =>
    apiFetch<EducationManagementDetail>(`${versionPath(moduleSlug, version)}/submit-review`, { method: 'POST' }),

  review: (moduleSlug: string, version: number, decision: 'approve' | 'reject', notes: string) =>
    apiFetch<EducationManagementDetail>(`${versionPath(moduleSlug, version)}/${decision}`, { method: 'POST', body: { notes } }),

  publish: (moduleSlug: string, version: number) =>
    apiFetch<EducationManagementDetail>(`${versionPath(moduleSlug, version)}/publish`, { method: 'POST' }),

  copyVersion: (moduleSlug: string, version: number) =>
    apiFetch<EducationManagementDetail>(`${versionPath(moduleSlug, version)}/copy`, { method: 'POST' }),

  previewMarkdown: (markdown: string) =>
    apiFetch<MarkdownPreviewResponse>('/api/content/education/markdown-preview', { method: 'POST', body: { markdown } }),
}
