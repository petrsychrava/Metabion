import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { i18n } from './i18n'
import { router } from './router'
import { setUnauthorizedHandler } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import './style.css'

const app = createApp(App).use(createPinia()).use(i18n).use(router)

// Expired session mid-use → reset local auth state and go to /login.
setUnauthorizedHandler(() => {
  useAuthStore().expire()
  if (router.currentRoute.value.path !== '/login') {
    void router.push('/login').catch(() => undefined)
  }
})

app.mount('#app')
