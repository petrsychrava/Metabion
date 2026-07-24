import { createI18n } from 'vue-i18n'
import en from './en.json'
import cs from './cs.json'

export type AppLocale = 'en' | 'cs'

export const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en, cs },
})

export function setLocale(locale: AppLocale): void {
  i18n.global.locale.value = locale
}
