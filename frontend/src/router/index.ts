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
