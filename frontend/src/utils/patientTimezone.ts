/**
 * Date helpers mirroring the backend's patient-timezone day boundaries
 * (MeasurementWindowService / log-date validation). The backend rejects dates
 * in the patient's future and measurements outside the log's patient-timezone
 * day, so the SPA must derive dates in the profile timezone, not the browser's.
 */

const NOON_HOUR = 12

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

function browserTodayIso(): string {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function datePartsInZone(instant: Date, timezone: string): { year: string; month: string; day: string } {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(instant)
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? ''
  return { year: get('year'), month: get('month'), day: get('day') }
}

/** Today's date (YYYY-MM-DD) in the given IANA timezone; falls back to the browser zone. */
export function todayInTimezone(timezone: string | null): string {
  if (timezone) {
    try {
      const { year, month, day } = datePartsInZone(new Date(), timezone)
      return `${year}-${month}-${day}`
    } catch {
      // Unknown/invalid zone id — fall through to the browser-local date.
    }
  }
  return browserTodayIso()
}

/** Milliseconds of the wall clock reading of `instant` in `timezone`, as a pseudo-UTC timestamp. */
function wallClockMillis(instant: Date, timezone: string): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).formatToParts(instant)
  const get = (type: string) => Number(parts.find((p) => p.type === type)?.value)
  return Date.UTC(get('year'), get('month') - 1, get('day'), get('hour') % 24, get('minute'), get('second'))
}

/** UTC instant of noon wall time on `dateIso` in `timezone`. */
function zonedNoonToUtc(dateIso: string, timezone: string): Date {
  const guess = new Date(`${dateIso}T${pad(NOON_HOUR)}:00:00Z`)
  const offset = wallClockMillis(guess, timezone) - guess.getTime()
  return new Date(guess.getTime() - offset)
}

/**
 * ISO instant guaranteed to fall inside `dateIso`'s day in the patient timezone:
 * the current instant when `dateIso` is the patient's today, otherwise noon of
 * that date. Without a known timezone, falls back to browser-local semantics.
 */
export function instantWithinDate(dateIso: string, timezone: string | null): string {
  if (todayInTimezone(timezone) === dateIso) {
    const now = new Date()
    now.setSeconds(0, 0)
    return now.toISOString()
  }
  if (timezone) {
    try {
      return zonedNoonToUtc(dateIso, timezone).toISOString()
    } catch {
      // Unknown/invalid zone id — fall through to browser-local noon.
    }
  }
  const noon = new Date(`${dateIso}T${pad(NOON_HOUR)}:00:00`)
  noon.setSeconds(0, 0)
  return noon.toISOString()
}
