import { describe, expect, it } from 'vitest'
import { instantWithinDate, todayInTimezone } from '@/utils/patientTimezone'

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
