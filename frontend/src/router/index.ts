import { createRouter, createWebHistory, type Router, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
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
import AppShell from '@/components/AppShell.vue'

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
      // Later tasks insert feature child routes here (paths without leading '/').
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
    if (to.meta.requiresAuth && !to.meta.allowStaff && auth.isAuthenticated && !auth.isPatient) {
      return { path: '/staff-notice' }
    }
    if (to.path === '/login' && auth.isAuthenticated) {
      return { path: '/' }
    }
    return true
  })
}

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

installAuthGuard(router)
