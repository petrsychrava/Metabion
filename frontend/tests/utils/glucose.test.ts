import { describe, expect, it } from 'vitest'
import { convertGlucose } from '@/utils/glucose'

describe('convertGlucose', () => {
  it('returns the value unchanged when units match', () => {
    expect(convertGlucose(5.2, 'MMOL_L', 'MMOL_L')).toBe(5.2)
    expect(convertGlucose(94, 'MG_DL', 'MG_DL')).toBe(94)
  })

  it('converts mmol/L to mg/dL with factor 18', () => {
    expect(convertGlucose(5, 'MMOL_L', 'MG_DL')).toBe(90)
  })

  it('converts mg/dL to mmol/L with factor 18', () => {
    expect(convertGlucose(180, 'MG_DL', 'MMOL_L')).toBe(10)
  })

  it('rounds to 2 decimal places half-up', () => {
    expect(convertGlucose(95, 'MG_DL', 'MMOL_L')).toBe(5.28)
    expect(convertGlucose(5.55, 'MMOL_L', 'MG_DL')).toBe(99.9)
  })
})
