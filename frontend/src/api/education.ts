import { apiFetch } from './http'
import type { EducationModuleDetail, EducationModuleSummary } from '@/types/api'

export const educationApi = {
  listModules: () => apiFetch<EducationModuleSummary[]>('/api/education/modules'),
  getModule: (moduleSlug: string) =>
    apiFetch<EducationModuleDetail>(`/api/education/modules/${encodeURIComponent(moduleSlug)}`),
  completeLesson: (moduleSlug: string, lessonSlug: string) =>
    apiFetch<void>(`/api/education/modules/${encodeURIComponent(moduleSlug)}/lessons/${encodeURIComponent(lessonSlug)}/complete`, { method: 'POST' }),
  uncompleteLesson: (moduleSlug: string, lessonSlug: string) =>
    apiFetch<void>(`/api/education/modules/${encodeURIComponent(moduleSlug)}/lessons/${encodeURIComponent(lessonSlug)}/complete`, { method: 'DELETE' }),
}
