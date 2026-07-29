import { ref } from 'vue'

export type ThemePreference = 'SYSTEM' | 'LIGHT' | 'DARK'

export const THEME_STORAGE_KEY = 'metabion.theme'

/** Reactive dark-state for non-CSS consumers (e.g. Chart.js colors). */
export const isDark = ref(false)

let current: ThemePreference = 'SYSTEM'
let listening = false

function resolve(pref: ThemePreference): boolean {
  if (pref === 'DARK') return true
  if (pref === 'LIGHT') return false
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

function apply(pref: ThemePreference): void {
  current = pref
  const dark = resolve(pref)
  document.documentElement.classList.toggle('dark', dark)
  isDark.value = dark
  if (listening) return
  listening = true
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (current === 'SYSTEM') apply('SYSTEM')
  })
}

export function setTheme(pref: ThemePreference): void {
  localStorage.setItem(THEME_STORAGE_KEY, pref)
  apply(pref)
}

export function initTheme(): void {
  const stored = localStorage.getItem(THEME_STORAGE_KEY)
  apply(stored === 'SYSTEM' || stored === 'LIGHT' || stored === 'DARK' ? stored : 'SYSTEM')
}

export function currentTheme(): ThemePreference {
  return current
}
