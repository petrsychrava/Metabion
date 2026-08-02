import { ref } from 'vue'
import { defineStore } from 'pinia'
import { redFlagApi } from '@/api/redFlags'
import type { PatientRedFlagSnapshot } from '@/types/api'

export const useRedFlagsStore = defineStore('redFlags', () => {
  const snapshot = ref<PatientRedFlagSnapshot | null>(null)
  const loading = ref(false)
  const loadFailed = ref(false)

  /**
   * Refreshes the current snapshot. A failure only hides the banner via
   * loadFailed — it never throws, so save flows are unaffected.
   */
  async function refreshCurrent(): Promise<void> {
    if (loading.value) return
    loading.value = true
    try {
      snapshot.value = await redFlagApi.getCurrent()
      loadFailed.value = false
    } catch {
      loadFailed.value = true
    } finally {
      loading.value = false
    }
  }

  function clear(): void {
    snapshot.value = null
    loading.value = false
    loadFailed.value = false
  }

  return { snapshot, loading, loadFailed, refreshCurrent, clear }
})
