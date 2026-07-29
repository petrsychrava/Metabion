import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'

// Default preferences so auth flows (login/fetchMe) can sync locale/theme without each test stubbing them.
export const server = setupServer(
  http.get('/api/account/preferences/language', () => HttpResponse.json({ language: 'EN' })),
  http.get('/api/account/preferences/theme', () => HttpResponse.json({ theme: 'SYSTEM' })),
)
