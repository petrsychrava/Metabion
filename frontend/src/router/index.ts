import { createRouter, createWebHistory, type Router, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LoginView from '@/views/LoginView.vue'
import StaffNoticeView from '@/views/StaffNoticeView.vue'
import DashboardView from '@/views/DashboardView.vue'

export const routes: RouteRecordRaw[] = [
  { path: '/', component: DashboardView, meta: { requiresAuth: true } },
  { path: '/login', component: LoginView },
  { path: '/staff-notice', component: StaffNoticeView, meta: { requiresAuth: true, allowStaff: true } },
  // Later tasks insert their feature routes here.
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
