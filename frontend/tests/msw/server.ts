import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'

// Default preference so auth flows (login/fetchMe) can sync locale without each test stubbing it.
export const server = setupServer(
  http.get('/api/account/preferences/language', () => HttpResponse.json({ language: 'EN' })),
)
