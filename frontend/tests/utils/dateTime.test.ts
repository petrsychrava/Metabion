import { describe, expect, it } from 'vitest'
import { formatDateTime } from '@/utils/dateTime'

describe('formatDateTime', () => {
  it('formats an ISO instant for the given locale', () => {
    const result = formatDateTime('2026-08-01T10:15:30Z', 'en')
    expect(result).toContain('2026')
    expect(result).not.toBe('2026-08-01T10:15:30Z')
  })
})
