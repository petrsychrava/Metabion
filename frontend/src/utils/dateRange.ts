export type DateRangeError = 'invalid' | 'too_long' | null

/** Validates SPA date-range pickers against the backend's DateRangeValidator rules. */
export function dateRangeError(from: string, to: string): DateRangeError {
  const fromMs = Date.parse(from)
  const toMs = Date.parse(to)
  if (!from || !to || Number.isNaN(fromMs) || Number.isNaN(toMs)) return 'invalid'
  if (fromMs > toMs) return 'invalid'
  const days = (toMs - fromMs) / 86_400_000
  return days > 370 ? 'too_long' : null
}
