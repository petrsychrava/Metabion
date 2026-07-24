import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@/api/http'

export function useApiError() {
  const { t, te } = useI18n()
  const message = ref('')
  const fieldErrors = ref<Record<string, string>>({})

  function clear(): void {
    message.value = ''
    fieldErrors.value = {}
  }

  function capture(e: unknown): void {
    clear()
    if (e instanceof ApiError) {
      fieldErrors.value = e.fields ?? {}
      if (!e.fields) {
        const key = `errors.${e.code}`
        message.value = te(key) ? t(key) : t('errors.request_failed')
      } else {
        message.value = t('errors.validation_failed')
      }
      return
    }
    message.value = t('errors.network')
  }

  return { message, fieldErrors, capture, clear }
}
