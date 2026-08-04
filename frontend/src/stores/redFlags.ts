import { ref } from 'vue'
import { defineStore } from 'pinia'
import { redFlagApi } from '@/api/redFlags'
import type { PatientRedFlagSnapshot } from '@/types/api'

export const useRedFlagsStore = defineStore('redFlags', () => {
  const snapshot = ref<PatientRedFlagSnapshot | null>(null)
  const loading = ref(false)
  const loadFailed = ref(false)
  let generation = 0
  let followUpPending = false

  /**
   * Refreshes the current snapshot. A failure only hides the banner via
   * loadFailed — it never throws, so save flows are unaffected. A result
   * that was in flight when clear() ran is discarded, so a previous
   * patient's flags cannot reappear after logout. A refresh requested while
   * one is in flight is coalesced into a single follow-up run, because the
   * in-flight response may predate the write that triggered the request.
   */
  async function refreshCurrent(): Promise<void> {
    if (loading.value) {
      followUpPending = true
      return
    }
    loading.value = true
    const gen = generation
    try {
      const result = await redFlagApi.getCurrent()
      if (gen !== generation) return // a clear() happened while in flight; discard
      snapshot.value = result
      loadFailed.value = false
    } catch {
      if (gen !== generation) return // a clear() happened while in flight; discard
      loadFailed.value = true
    } finally {
      // Only the invocation that still owns the active generation may touch
      // shared state: a stale request settling after clear() must neither
      // release loading nor consume a follow-up queued for the newer refresh.
      if (gen === generation) {
        loading.value = false
        const followUp = followUpPending
        followUpPending = false
        if (followUp) await refreshCurrent()
      }
    }
  }

  function clear(): void {
    generation += 1
    followUpPending = false
    snapshot.value = null
    loading.value = false
    loadFailed.value = false
  }

  return { snapshot, loading, loadFailed, refreshCurrent, clear }
})
