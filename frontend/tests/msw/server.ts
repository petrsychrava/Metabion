import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'

// Default preferences so auth flows (login/fetchMe) can sync locale/theme without each test stubbing them.
export const server = setupServer(
  http.get('/api/account/preferences/language', () => HttpResponse.json({ language: 'EN' })),
  http.get('/api/account/preferences/theme', () => HttpResponse.json({ theme: 'SYSTEM' })),
  // Default profile in the browser's own zone so timezone-aware views behave
  // exactly as before unless a test stubs a different zone.
  http.get('/api/account/profile', () => HttpResponse.json({
    dateOfBirth: '1990-01-01',
    sex: 'PREFER_NOT_TO_SAY',
    countryRegion: 'CZ',
    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
  })),
)
