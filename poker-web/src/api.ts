let csrfHeader = 'X-XSRF-TOKEN'
let csrfToken = ''

async function loadCsrf() {
  const response = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (!response.ok) throw new Error('无法连接服务器')
  const data = await response.json()
  csrfHeader = data.headerName
  csrfToken = data.token
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase()
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && !csrfToken) await loadCsrf()
  const headers = new Headers(options.headers)
  if (options.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) headers.set(csrfHeader, csrfToken)

  let response = await fetch(path, { ...options, headers, credentials: 'include' })
  if (response.status === 403 && !['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    await loadCsrf()
    headers.set(csrfHeader, csrfToken)
    response = await fetch(path, { ...options, headers, credentials: 'include' })
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({ message: response.status === 401 ? '请先登录' : '请求失败' }))
    throw new Error(body.message ?? '请求失败')
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export async function refreshCsrf() {
  await loadCsrf()
}

