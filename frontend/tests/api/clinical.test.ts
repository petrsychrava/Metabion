import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../msw/server'
import { clinicalApi } from '@/api/clinical'

describe('clinicalApi', () => {
  it('requests the overview', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/clinical/overview', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json([])
      }),
    )
    const rows = await clinicalApi.overview()
    expect(rows).toEqual([])
    expect(seenUrl).toContain('/api/clinical/overview')
  })

  it('builds the daily check-ins query string', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/clinical/daily-check-ins', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json([])
      }),
    )
    await clinicalApi.listDailyCheckIns(41, '2026-07-28', '2026-08-03')
    expect(seenUrl).toContain('patientProfileId=41')
    expect(seenUrl).toContain('from=2026-07-28')
    expect(seenUrl).toContain('to=2026-08-03')
  })

  it('builds the red-flag history query string', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/clinical/patients/41/red-flags/history', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
    )
    await clinicalApi.redFlagHistory(41, { from: '2026-07-01', severity: 'EMERGENCY', size: 25 })
    expect(seenUrl).toContain('from=2026-07-01')
    expect(seenUrl).toContain('severity=EMERGENCY')
    expect(seenUrl).toContain('size=25')
    expect(seenUrl).not.toContain('cursor=')
  })

  it('posts the onboarding review body', async () => {
    let received: unknown
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/clinical/onboarding/submissions/9/review', async ({ request }) => {
        received = await request.json()
        return HttpResponse.json({ id: 9 })
      }),
    )
    await clinicalApi.reviewOnboardingSubmission(9, { reviewStatus: 'REVIEWED', reviewNotes: 'ok' })
    expect(received).toEqual({ reviewStatus: 'REVIEWED', reviewNotes: 'ok' })
  })

  it('builds onboarding list filters', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/clinical/onboarding/submissions', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json([])
      }),
    )
    await clinicalApi.listOnboardingSubmissions(undefined, 'PENDING_REVIEW')
    expect(seenUrl).toContain('status=PENDING_REVIEW')
    expect(seenUrl).not.toContain('context=')
  })
})
