export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    public readonly fields?: Record<string, string>,
  ) {
    super(`${status} ${code}`)
    this.name = 'ApiError'
  }
}

export interface ApiFetchOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  formData?: FormData
  csrf?: boolean
}

interface CsrfState {
  token: string
  headerName: string
}

let csrf: CsrfState | null = null

export function resetCsrfToken(): void {
  csrf = null
}

type UnauthorizedHandler = () => void
let unauthorizedHandler: UnauthorizedHandler | null = null

/** Called whenever any request returns 401 (e.g. expired session mid-use). Wired in main.ts (Task 3). */
export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler
}

async function ensureCsrf(): Promise<CsrfState> {
  if (!csrf) {
    const res = await fetch('/api/csrf', { credentials: 'same-origin' })
    if (!res.ok) {
      if (res.status === 401) unauthorizedHandler?.()
      throw new ApiError(res.status, 'unauthorized')
    }
    csrf = (await res.json()) as CsrfState
  }
  return csrf
}

async function parseBody(res: Response): Promise<unknown> {
  const text = await res.text()
  if (!text) return undefined
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

export async function apiFetch<T>(path: string, options: ApiFetchOptions = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = {}

  if (method !== 'GET' && options.csrf !== false) {
    const { token, headerName } = await ensureCsrf()
    headers[headerName] = token
  }

  let body: BodyInit | undefined
  if (options.formData !== undefined) {
    body = options.formData
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }

  const res = await fetch(path, { method, headers, body, credentials: 'same-origin' })
  const data = await parseBody(res)

  if (!res.ok) {
    if (res.status === 401) unauthorizedHandler?.()
    const errBody = data as { error?: string; fields?: Record<string, string> } | undefined
    throw new ApiError(res.status, errBody?.error ?? (res.status === 401 ? 'unauthorized' : 'request_failed'), errBody?.fields)
  }
  return data as T
}
