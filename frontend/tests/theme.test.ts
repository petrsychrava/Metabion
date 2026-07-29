import { beforeEach, describe, expect, it, vi } from 'vitest'

type ChangeCallback = (e: { matches: boolean }) => void

function stubMatchMedia(matches: boolean) {
  const listeners = new Set<ChangeCallback>()
  const state = { matches }
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: (query: string) => ({
      get matches() {
        return state.matches
      },
      media: query,
      addEventListener: (_: string, cb: ChangeCallback) => listeners.add(cb),
      removeEventListener: (_: string, cb: ChangeCallback) => listeners.delete(cb),
    }),
  })
  return {
    fire: (m: boolean) => {
      state.matches = m
      listeners.forEach((cb) => cb({ matches: m }))
    },
  }
}

describe('theme', () => {
  beforeEach(() => {
    vi.resetModules()
    localStorage.clear()
    document.documentElement.classList.remove('dark')
  })

  it('defaults to SYSTEM and follows the OS when nothing is stored', async () => {
    stubMatchMedia(true)
    const { initTheme, THEME_STORAGE_KEY, currentTheme, isDark } = await import('@/theme')
    initTheme()
    expect(currentTheme()).toBe('SYSTEM')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(isDark.value).toBe(true)
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull()
  })

  it('restores a stored preference', async () => {
    stubMatchMedia(true)
    localStorage.setItem('metabion.theme', 'LIGHT')
    const { initTheme, currentTheme } = await import('@/theme')
    initTheme()
    expect(currentTheme()).toBe('LIGHT')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('falls back to SYSTEM for an unknown stored value', async () => {
    stubMatchMedia(false)
    localStorage.setItem('metabion.theme', 'neon')
    const { initTheme, currentTheme } = await import('@/theme')
    initTheme()
    expect(currentTheme()).toBe('SYSTEM')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('setTheme persists and applies immediately', async () => {
    stubMatchMedia(false)
    const { setTheme, currentTheme, isDark } = await import('@/theme')
    setTheme('DARK')
    expect(currentTheme()).toBe('DARK')
    expect(localStorage.getItem('metabion.theme')).toBe('DARK')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(isDark.value).toBe(true)
  })

  it('SYSTEM re-resolves when the OS preference changes', async () => {
    const media = stubMatchMedia(false)
    const { initTheme, setTheme } = await import('@/theme')
    initTheme()
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    media.fire(true)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    setTheme('LIGHT')
    media.fire(false)
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })
})
