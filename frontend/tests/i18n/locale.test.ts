import { beforeEach, describe, expect, it } from 'vitest'
import { i18n, initLocale, LOCALE_STORAGE_KEY } from '@/i18n'
import en from '@/i18n/en.json'
import cs from '@/i18n/cs.json'

function flattenKeys(bundle: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(bundle).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      return flattenKeys(value as Record<string, unknown>, path)
    }
    return [path]
  })
}

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

describe('locale key parity', () => {
  it('en and cs expose the same flattened key set', () => {
    const enKeys = flattenKeys(en)
    const csKeys = flattenKeys(cs)
    const missingInCs = enKeys.filter((key) => !csKeys.includes(key))
    const missingInEn = csKeys.filter((key) => !enKeys.includes(key))
    expect(missingInCs).toEqual([])
    expect(missingInEn).toEqual([])
  })
})
