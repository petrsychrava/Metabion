export type DateRangeError = 'invalid' | 'too_long' | null

/**
 * Validates SPA date-range pickers against the backend's DateRangeValidator rules.
 * Pass a smaller maxDays for endpoints with a tighter inclusive limit
 * (the red-flag history endpoint rejects ranges over 369 days apart).
 */
export function dateRangeError(from: string, to: string, maxDays = 370): DateRangeError {
  const fromMs = Date.parse(from)
  const toMs = Date.parse(to)
  if (!from || !to || Number.isNaN(fromMs) || Number.isNaN(toMs)) return 'invalid'
  if (fromMs > toMs) return 'invalid'
  const days = (toMs - fromMs) / 86_400_000
  return days > maxDays ? 'too_long' : null
}
