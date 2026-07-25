import { beforeEach, describe, expect, it } from 'vitest'
import { i18n, initLocale, LOCALE_STORAGE_KEY } from '@/i18n'

describe('initLocale', () => {
  beforeEach(() => {
    localStorage.clear()
    i18n.global.locale.value = 'en'
  })

  it('restores the persisted locale from localStorage', () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, 'cs')
    initLocale()
    expect(i18n.global.locale.value).toBe('cs')
  })

  it('keeps the default locale when nothing is stored', () => {
    initLocale()
    expect(i18n.global.locale.value).toBe('en')
  })

  it('ignores an unknown stored value', () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, 'klingon')
    initLocale()
    expect(i18n.global.locale.value).toBe('en')
  })
})
