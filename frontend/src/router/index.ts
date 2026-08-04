import { createRouter, createWebHistory, type Router, type RouteRecordRaw } from 'vue-router'
import { CLINICAL_ROLES, useAuthStore } from '@/stores/auth'
import LoginView from '@/views/LoginView.vue'
import RegisterView from '@/views/RegisterView.vue'
import ForgotPasswordView from '@/views/ForgotPasswordView.vue'
import ResetPasswordView from '@/views/ResetPasswordView.vue'
import VerifyEmailView from '@/views/VerifyEmailView.vue'
import StaffNoticeView from '@/views/StaffNoticeView.vue'
import DashboardView from '@/views/DashboardView.vue'
import AccountProfileView from '@/views/AccountProfileView.vue'
import AccessTokensView from '@/views/AccessTokensView.vue'
import DietLogEditView from '@/views/DietLogEditView.vue'
import DietLogHistoryView from '@/views/DietLogHistoryView.vue'
import CheckInEditView from '@/views/CheckInEditView.vue'
import CheckInListView from '@/views/CheckInListView.vue'
import TrendsView from '@/views/TrendsView.vue'
import LabResultSetsView from '@/views/LabResultSetsView.vue'
import LabResultSetEditView from '@/views/LabResultSetEditView.vue'
import LabTrendsView from '@/views/LabTrendsView.vue'
import OnboardingView from '@/views/OnboardingView.vue'
import EducationListView from '@/views/EducationListView.vue'
import EducationModuleView from '@/views/EducationModuleView.vue'
import RedFlagsView from '@/views/RedFlagsView.vue'
import AppShell from '@/components/AppShell.vue'
import ClinicalShell from '@/components/ClinicalShell.vue'
import ClinicalStubView from '@/views/clinical/ClinicalStubView.vue'
import ClinicalOverviewView from '@/views/clinical/ClinicalOverviewView.vue'
import ClinicalPatientWorkspaceView from '@/views/clinical/ClinicalPatientWorkspaceView.vue'
import ClinicalCheckInsView from '@/views/clinical/ClinicalCheckInsView.vue'
import ClinicalCheckInDayView from '@/views/clinical/ClinicalCheckInDayView.vue'
import ClinicalPatientTrendsView from '@/views/clinical/ClinicalPatientTrendsView.vue'
import ClinicalPatientLabsView from '@/views/clinical/ClinicalPatientLabsView.vue'

export const routes: RouteRecordRaw[] = [
  { path: '/login', component: LoginView },
  { path: '/register', component: RegisterView },
  { path: '/forgot-password', component: ForgotPasswordView },
  { path: '/reset-password', component: ResetPasswordView },
  { path: '/verify', component: VerifyEmailView },
  {
    path: '/',
    component: AppShell,
    meta: { requiresAuth: true },
    children: [
      { path: '', component: DashboardView, meta: { requiresAuth: true } },
      { path: 'account', component: AccountProfileView, meta: { requiresAuth: true } },
      { path: 'account/access-tokens', component: AccessTokensView, meta: { requiresAuth: true } },
      { path: 'diet-logs', component: DietLogHistoryView, meta: { requiresAuth: true } },
      { path: 'diet-logs/:date', component: DietLogEditView, meta: { requiresAuth: true } },
      { path: 'check-ins', component: CheckInListView, meta: { requiresAuth: true } },
      { path: 'check-ins/:date', component: CheckInEditView, meta: { requiresAuth: true } },
      { path: 'trends', component: TrendsView, meta: { requiresAuth: true } },
      { path: 'labs', component: LabResultSetsView, meta: { requiresAuth: true } },
      { path: 'labs/new', component: LabResultSetEditView, meta: { requiresAuth: true } },
      { path: 'labs/trends', component: LabTrendsView, meta: { requiresAuth: true } },
      { path: 'labs/:id', component: LabResultSetEditView, meta: { requiresAuth: true } },
      { path: 'onboarding', component: OnboardingView, meta: { requiresAuth: true } },
      { path: 'education', component: EducationListView, meta: { requiresAuth: true } },
      { path: 'education/:moduleSlug', component: EducationModuleView, meta: { requiresAuth: true } },
      { path: 'red-flags', component: RedFlagsView, meta: { requiresAuth: true } },
      // Later tasks insert feature child routes here (paths without leading '/').
    ],
  },
  {
    path: '/clinical',
    component: ClinicalShell,
    meta: { requiresAuth: true, roles: CLINICAL_ROLES },
    children: [
      { path: '', component: ClinicalOverviewView },
      { path: 'onboarding', component: ClinicalStubView },
      { path: 'onboarding/:submissionId', component: ClinicalStubView },
      { path: 'education', component: EducationListView },
      { path: 'education/:moduleSlug', component: EducationModuleView },
      {
        path: 'patients/:patientProfileId',
        component: ClinicalPatientWorkspaceView,
        children: [
          { path: '', redirect: (to) => `/clinical/patients/${to.params.patientProfileId}/check-ins` },
          { path: 'check-ins', component: ClinicalCheckInsView },
          { path: 'check-ins/:date', component: ClinicalCheckInDayView },
          { path: 'trends', component: ClinicalPatientTrendsView },
          { path: 'labs', component: ClinicalPatientLabsView },
          { path: 'labs/new', component: ClinicalStubView },
          { path: 'labs/:resultSetId', component: ClinicalStubView },
          { path: 'red-flags', component: ClinicalStubView },
          { path: 'onboarding', component: ClinicalStubView },
        ],
      },
    ],
  },
  { path: '/staff-notice', component: StaffNoticeView, meta: { requiresAuth: true, allowStaff: true } },
  { path: '/:pathMatch(.*)*', component: () => import('@/views/NotFoundView.vue'), meta: { requiresAuth: true } },
]

export function installAuthGuard(router: Router): void {
  router.beforeEach(async (to) => {
    const auth = useAuthStore()
    if (auth.status === 'unknown') {
      await auth.fetchMe()
    }
    if (to.meta.requiresAuth && !auth.isAuthenticated) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    if (to.path === '/login' && auth.isAuthenticated) {
      return { path: auth.homePath }
    }
    if (to.meta.requiresAuth && auth.isAuthenticated) {
      const requiredRoles = to.meta.roles as string[] | undefined
      if (requiredRoles !== undefined) {
        // Route meta merges across matched records, so children inherit the parent's roles.
        return requiredRoles.some((role) => auth.roles.includes(role))
          ? true
          : { path: auth.isPatient ? '/' : '/staff-notice' }
      }
      if (!to.meta.allowStaff && !auth.isPatient) {
        return { path: auth.homePath }
      }
    }
    return true
  })
}

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

installAuthGuard(router)
