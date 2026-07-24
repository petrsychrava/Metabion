import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './msw/server'
import { resetCsrfToken } from '@/api/http'

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetCsrfToken()
})
afterAll(() => server.close())
