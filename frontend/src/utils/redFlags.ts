import type { RedFlagSeverity } from '@/types/api'

const BADGE_CLASSES: Record<RedFlagSeverity, string> = {
  EMERGENCY: 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-200',
  URGENT_REVIEW: 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-200',
  ROUTINE_REVIEW: 'bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-200',
}

/** Tailwind class set for a severity-colored strip or badge. */
export function severityBadgeClass(severity: RedFlagSeverity): string {
  return BADGE_CLASSES[severity]
}
