import { createI18n } from 'vue-i18n'
import en from './en.json'
import cs from './cs.json'

export type AppLocale = 'en' | 'cs'

export const LOCALE_STORAGE_KEY = 'metabion.locale'

export const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en, cs },
})

export function setLocale(locale: AppLocale): void {
  i18n.global.locale.value = locale
  localStorage.setItem(LOCALE_STORAGE_KEY, locale)
}

export function initLocale(): void {
  const stored = localStorage.getItem(LOCALE_STORAGE_KEY)
  if (stored === 'en' || stored === 'cs') {
    setLocale(stored)
  }
}
