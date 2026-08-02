/** Formats an ISO instant for display in the active UI locale. */
export function formatDateTime(iso: string, locale: string): string {
  return new Date(iso).toLocaleString(locale)
}
