import test from 'node:test'
import assert from 'node:assert/strict'
import { logoutSafely, shouldForgetSession } from '../src/lib/failurePolicy.js'
import { ApiError, useApi } from '../src/composables/useApi.js'

test('history only forgets confirmed missing or forbidden sessions', () => {
  assert.equal(shouldForgetSession(new ApiError('missing', 404)), true)
  assert.equal(shouldForgetSession(new ApiError('foreign', 403)), true)
  assert.equal(shouldForgetSession(new ApiError('server', 500)), false)
  assert.equal(shouldForgetSession(new TypeError('network unavailable')), false)
})

test('logout failure preserves local auth, while success or expired session ends it', async () => {
  let ended = 0
  const clear = () => { ended += 1 }
  await assert.rejects(logoutSafely(async () => { throw new ApiError('server', 500) }, clear), ApiError)
  assert.equal(ended, 0)
  await logoutSafely(async () => {}, clear)
  assert.equal(ended, 1)
  await logoutSafely(async () => { throw new ApiError('expired', 401) }, clear)
  assert.equal(ended, 2)
})

test('API errors preserve HTTP status and notify only for authentication failures', async () => {
  let unauthorized = 0
  let forbidden = ''
  const api = useApi({ onUnauthorized: () => { unauthorized += 1 }, onForbidden: (message) => { forbidden = message } })
  await assert.rejects(api.readApiResponse(new Response('{"message":"busy"}', { status: 500 })),
    (error) => error instanceof ApiError && error.status === 500)
  assert.equal(unauthorized, 0)
  await assert.rejects(api.readApiResponse(new Response('{"message":"denied"}', { status: 403 })),
    (error) => error instanceof ApiError && error.status === 403)
  assert.equal(forbidden, 'denied')
  await assert.rejects(api.readApiResponse(new Response('{"message":"expired"}', { status: 401 })),
    (error) => error instanceof ApiError && error.status === 401)
  assert.equal(unauthorized, 1)
  await assert.rejects(api.readApiResponse(new Response('{"message":"bad credentials"}', { status: 401 }),
    { handleUnauthorized: false }), (error) => error instanceof ApiError && error.status === 401)
  assert.equal(unauthorized, 1)
})
