import { ref } from 'vue'
import { defineStore } from 'pinia'
import { redFlagApi } from '@/api/redFlags'
import type { PatientRedFlagSnapshot } from '@/types/api'

export const useRedFlagsStore = defineStore('redFlags', () => {
  const snapshot = ref<PatientRedFlagSnapshot | null>(null)
  const loading = ref(false)
  const loadFailed = ref(false)
  let generation = 0

  /**
   * Refreshes the current snapshot. A failure only hides the banner via
   * loadFailed — it never throws, so save flows are unaffected. A result
   * that was in flight when clear() ran is discarded, so a previous
   * patient's flags cannot reappear after logout.
   */
  async function refreshCurrent(): Promise<void> {
    if (loading.value) return
    loading.value = true
    const gen = generation
    try {
      const result = await redFlagApi.getCurrent()
      if (gen !== generation) return // a clear() happened while in flight; discard
      snapshot.value = result
      loadFailed.value = false
    } catch {
      loadFailed.value = true
    } finally {
      loading.value = false
    }
  }

  function clear(): void {
    generation += 1
    snapshot.value = null
    loading.value = false
    loadFailed.value = false
  }

  return { snapshot, loading, loadFailed, refreshCurrent, clear }
})
