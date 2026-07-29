import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../msw/server'
import { accountApi } from '@/api/account'

describe('accountApi theme preference', () => {
  it('fetches the current theme preference', async () => {
    server.use(http.get('/api/account/preferences/theme', () => HttpResponse.json({ theme: 'DARK' })))
    const pref = await accountApi.getThemePreference()
    expect(pref.theme).toBe('DARK')
  })

  it('updates the theme preference', async () => {
    let putBody: unknown
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/account/preferences/theme', async ({ request }) => {
        putBody = await request.json()
        return HttpResponse.json({ status: 'ok' })
      }),
    )
    const res = await accountApi.updateThemePreference('DARK')
    expect(res.status).toBe('ok')
    expect(putBody).toEqual({ theme: 'DARK' })
  })
})
