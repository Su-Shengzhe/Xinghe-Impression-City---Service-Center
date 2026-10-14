/** Only a confirmed missing or foreign session is removed from browser navigation. */
export function shouldForgetSession(error) {
  return error?.status === 404 || error?.status === 403
}

/** A network/5xx logout failure leaves local auth intact; 401 means it already expired. */
export async function logoutSafely(request, onEnded) {
  try {
    await request('/api/auth/logout', { method: 'POST' })
  } catch (error) {
    if (error?.status !== 401) throw error
  }
  onEnded()
}
