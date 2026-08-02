import { describe, expect, it } from 'vitest'
import { severityBadgeClass } from '@/utils/redFlags'

describe('severityBadgeClass', () => {
  it('maps each severity to a distinct severity-colored class set', () => {
    expect(severityBadgeClass('EMERGENCY')).toContain('red')
    expect(severityBadgeClass('URGENT_REVIEW')).toContain('amber')
    expect(severityBadgeClass('ROUTINE_REVIEW')).toContain('blue')
  })
})
