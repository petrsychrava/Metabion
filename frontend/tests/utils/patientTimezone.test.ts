import { describe, expect, it } from 'vitest'
import { formatForDateTimeInput, instantWithinDate, parseDateTimeInput, todayInTimezone } from '@/utils/patientTimezone'

function browserTodayIso(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

describe('todayInTimezone', () => {
  it('returns a YYYY-MM-DD date', () => {
    expect(todayInTimezone('Europe/Prague')).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })

  it('the earliest zone is never behind the latest zone', () => {
    // Pacific/Kiritimati (UTC+14) starts every calendar day first,
    // Etc/GMT+12 (UTC-12) starts it last.
    expect(todayInTimezone('Pacific/Kiritimati') >= todayInTimezone('Etc/GMT+12')).toBe(true)
  })

  it('falls back to the browser-local date for null or invalid zones', () => {
    expect(todayInTimezone(null)).toBe(browserTodayIso())
    expect(todayInTimezone('Not/AZone')).toBe(browserTodayIso())
  })
})

describe('instantWithinDate', () => {
  it('returns the current instant when the date is today in the zone', () => {
    const today = todayInTimezone('UTC')
    const before = Date.now()
    const result = Date.parse(instantWithinDate(today, 'UTC'))
    const after = Date.now()
    // Seconds are truncated to the minute, so the result can lag "before".
    expect(result).toBeGreaterThanOrEqual(before - 60_000)
    expect(result).toBeLessThanOrEqual(after + 1000)
  })

  it('returns UTC noon for a past date in the UTC zone', () => {
    expect(instantWithinDate('2020-01-15', 'UTC')).toBe('2020-01-15T12:00:00.000Z')
  })

  it('converts noon in a non-UTC zone to the matching UTC instant', () => {
    // Europe/Prague is UTC+2 (CEST) in June, so local noon is 10:00Z.
    expect(instantWithinDate('2020-06-15', 'Europe/Prague')).toBe('2020-06-15T10:00:00.000Z')
  })

  it('always lands inside the requested day in the given zone', () => {
    for (const [dateIso, tz] of [
      ['2020-01-15', 'Europe/Prague'],
      ['2020-06-15', 'Pacific/Kiritimati'],
      ['2020-03-08', 'America/New_York'], // DST start
      ['2020-10-25', 'Europe/Prague'], // DST end
    ] as const) {
      const local = new Intl.DateTimeFormat('en-US', {
        timeZone: tz,
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
      }).formatToParts(new Date(instantWithinDate(dateIso, tz)))
      const get = (type: string) => local.find((p) => p.type === type)?.value
      expect(`${get('year')}-${get('month')}-${get('day')}`).toBe(dateIso)
    }
  })

  it('falls back to browser-local noon for invalid zones', () => {
    const result = instantWithinDate('2020-01-15', 'Not/AZone')
    expect(result).toBe(new Date('2020-01-15T12:00:00').toISOString())
  })
})

describe('formatForDateTimeInput', () => {
  it('formats the instant as wall time in the given zone', () => {
    // Europe/Prague is UTC+1 (CET) in January, so 10:00Z reads as 11:00.
    expect(formatForDateTimeInput('2020-01-15T10:00:00.000Z', 'Europe/Prague')).toBe('2020-01-15T11:00')
  })

  it('keeps the patient-zone calendar date when it differs from the browser zone', () => {
    // 2020-01-15T23:30Z is still Jan 15 in Etc/GMT+12 but Jan 16 in Pacific/Kiritimati.
    expect(formatForDateTimeInput('2020-01-15T23:30:00.000Z', 'Etc/GMT+12')).toBe('2020-01-15T11:30')
    expect(formatForDateTimeInput('2020-01-15T23:30:00.000Z', 'Pacific/Kiritimati')).toBe('2020-01-16T13:30')
  })

  it('falls back to browser-local formatting for null or invalid zones', () => {
    const iso = '2020-06-15T10:00:00.000Z'
    const d = new Date(iso)
    const pad = (n: number) => String(n).padStart(2, '0')
    const expected = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
    expect(formatForDateTimeInput(iso, null)).toBe(expected)
    expect(formatForDateTimeInput(iso, 'Not/AZone')).toBe(expected)
  })
})

describe('parseDateTimeInput', () => {
  it('interprets the input as wall time in the given zone', () => {
    // Europe/Prague is UTC+2 (CEST) in June, so 12:00 local is 10:00Z.
    expect(parseDateTimeInput('2020-06-15T12:00', 'Europe/Prague')).toBe('2020-06-15T10:00:00.000Z')
  })

  it('round-trips with formatForDateTimeInput', () => {
    for (const [iso, tz] of [
      ['2020-01-15T10:00:00.000Z', 'Europe/Prague'],
      ['2020-06-15T22:30:00.000Z', 'America/New_York'],
      ['2020-10-25T03:30:00.000Z', 'Europe/Prague'], // DST end day, unambiguous hour
    ] as const) {
      expect(parseDateTimeInput(formatForDateTimeInput(iso, tz), tz)).toBe(iso)
    }
  })

  it('resolves the repeated autumn DST hour to the later occurrence', () => {
    // 02:30 occurs twice on 2020-10-25 in Europe/Prague (00:30Z and 01:30Z).
    expect(parseDateTimeInput('2020-10-25T02:30', 'Europe/Prague')).toBe('2020-10-25T01:30:00.000Z')
  })

  it('falls back to browser-local parsing for null or invalid zones', () => {
    const value = '2020-01-15T08:15'
    const expected = new Date(value).toISOString()
    expect(parseDateTimeInput(value, null)).toBe(expected)
    expect(parseDateTimeInput(value, 'Not/AZone')).toBe(expected)
  })
})
