import { describe, expect, it } from 'vitest'
import { dateRangeError } from '@/utils/dateRange'

describe('dateRangeError', () => {
  it('returns null for a valid 5-day range', () => {
    expect(dateRangeError('2025-01-01', '2025-01-06')).toBeNull()
  })

  it('returns null for exactly 370 days', () => {
    expect(dateRangeError('2024-01-01', '2025-01-05')).toBeNull()
  })

  it('returns too_long for 371 days', () => {
    expect(dateRangeError('2024-01-01', '2025-01-06')).toBe('too_long')
  })

  it('returns invalid when from is after to', () => {
    expect(dateRangeError('2025-02-01', '2025-01-01')).toBe('invalid')
  })

  it('returns invalid when from is empty', () => {
    expect(dateRangeError('', '2025-01-01')).toBe('invalid')
  })

  it('returns invalid for a garbage date string', () => {
    expect(dateRangeError('not-a-date', '2025-01-01')).toBe('invalid')
    expect(dateRangeError('2025-01-01', 'garbage')).toBe('invalid')
  })
})
