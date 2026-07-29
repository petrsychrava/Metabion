import type { MeasurementUnit } from '@/types/api'

/** Mirrors the backend TrendGlucoseConverter: 1 mmol/L = 18 mg/dL, 2 dp half-up. */
export function convertGlucose(value: number, source: MeasurementUnit, target: MeasurementUnit): number {
  if (source === target) return value
  const converted = source === 'MMOL_L' ? value * 18 : value / 18
  return Math.round(converted * 100) / 100
}
