import { ref } from 'vue'

export class ApiError extends Error {
  constructor(message, status) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export function useApi({ onUnauthorized, onForbidden }) {
  const csrfToken = ref('')

  function readCookie(name) {
    const prefix = `${encodeURIComponent(name)}=`
    const value = document.cookie.split('; ').find((entry) => entry.startsWith(prefix))
    return value ? decodeURIComponent(value.slice(prefix.length)) : ''
  }

  async function readApiResponse(response, { handleUnauthorized = true } = {}) {
    const raw = await response.text()
    let result
    try { result = raw ? JSON.parse(raw) : null } catch { result = null }
    if (response.status === 401 && handleUnauthorized) onUnauthorized()
    else if (response.status === 403) onForbidden(result?.message || '当前账号无权执行此操作')
    if (!response.ok) throw new ApiError(result?.message || `请求失败（${response.status}）`, response.status)
    if (result && typeof result === 'object' && 'code' in result && result.code !== 0) {
      throw new ApiError(result.message || '服务暂时不可用', response.status)
    }
    return result && typeof result === 'object' && 'data' in result ? result.data : result
  }

  async function ensureCsrfToken() {
    const cookieToken = readCookie('XSRF-TOKEN')
    if (cookieToken) return cookieToken
    if (csrfToken.value) return csrfToken.value
    const response = await fetch('/api/auth/csrf', { credentials: 'include' })
    const data = await readApiResponse(response, { handleUnauthorized: false })
    csrfToken.value = data || readCookie('XSRF-TOKEN') || ''
    if (!csrfToken.value) throw new Error('无法获取安全令牌，请刷新页面重试')
    return csrfToken.value
  }

  function buildRequestHeaders(extra = {}, method = 'GET') {
    const result = { ...extra }
    if (method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS') {
      const token = readCookie('XSRF-TOKEN') || csrfToken.value
      if (token) result['X-XSRF-TOKEN'] = token
    }
    return result
  }

  async function apiGet(url, options) {
    const response = await fetch(url, { credentials: 'include' })
    return readApiResponse(response, options)
  }

  async function apiRequest(url, { method = 'GET', body, headers: extra = {}, handleUnauthorized = true } = {}) {
    const requestHeaders = { ...extra }
    if (body !== undefined) requestHeaders['Content-Type'] = 'application/json'
    if (method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS') await ensureCsrfToken()
    const response = await fetch(url, {
      method,
      credentials: 'include',
      headers: buildRequestHeaders(requestHeaders, method),
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    })
    return readApiResponse(response, { handleUnauthorized })
  }

  return { csrfToken, ensureCsrfToken, buildRequestHeaders, readApiResponse, apiGet, apiRequest }
}
