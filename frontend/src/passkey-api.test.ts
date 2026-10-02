import { afterEach, expect, it, vi } from 'vitest'
import { registerPasskey, signInWithPasskey } from './passkey-api'

afterEach(() => { vi.unstubAllGlobals() })

function setupCredentialApi(createOrGet: 'create' | 'get') {
  const credential = {
    id: 'credential', rawId: new Uint8Array([7, 8]).buffer, type: 'public-key',
    response: {
      attestationObject: new Uint8Array([9]).buffer,
      authenticatorData: new Uint8Array([10]).buffer,
      clientDataJSON: new Uint8Array([11]).buffer,
      signature: new Uint8Array([12]).buffer,
      userHandle: null,
      getTransports: () => ['internal'],
    },
    getClientExtensionResults: () => ({}),
    authenticatorAttachment: 'platform',
  }
  const action = vi.fn(async (_options: CredentialCreationOptions | CredentialRequestOptions) => {
    void _options
    return credential
  })
  vi.stubGlobal('PublicKeyCredential', function PublicKeyCredential() {})
  Object.defineProperty(navigator, 'credentials', {
    configurable: true, value: { [createOrGet]: action },
  })
  return action
}

it('decodes wrapped registration options and encodes the WebAuthn result', async () => {
  const create = setupCredentialApi('create')
  const fetch = vi.fn()
    .mockResolvedValueOnce({ ok: true, json: async () => ({ token: 'ceremony', options: {
      publicKey: { challenge: 'AQID', rp: { id: 'localhost', name: 'Harbour' },
        user: { id: 'BAUG', name: 'member@example.com', displayName: 'Member' },
        pubKeyCredParams: [{ type: 'public-key', alg: -7 }],
        excludeCredentials: [{ type: 'public-key', id: 'BwgJ' }] },
    } }) })
    .mockResolvedValueOnce({ ok: true, json: async () => ({ stage: 'AUTHENTICATED' }) })
  vi.stubGlobal('fetch', fetch)

  await expect(registerPasskey('setup-token')).resolves.toMatchObject({ stage: 'AUTHENTICATED' })
  const options = create.mock.calls[0][0] as { publicKey: PublicKeyCredentialCreationOptions }
  expect([...new Uint8Array(options.publicKey.challenge as ArrayBuffer)]).toEqual([1, 2, 3])
  expect([...new Uint8Array(options.publicKey.user.id as ArrayBuffer)]).toEqual([4, 5, 6])
  expect([...new Uint8Array(options.publicKey.excludeCredentials![0].id as ArrayBuffer)]).toEqual([7, 8, 9])
  const sent = JSON.parse(fetch.mock.calls[1][1].body as string)
  expect(sent.challenge).toBe('setup-token')
  expect(sent.credential.rawId).toBe('Bwg')
  expect(sent.credential.response.attestationObject).toBe('CQ')
})

it('decodes wrapped assertion options for discoverable passkey sign-in', async () => {
  const get = setupCredentialApi('get')
  const fetch = vi.fn()
    .mockResolvedValueOnce({ ok: true, json: async () => ({ token: 'ceremony', options: {
      publicKey: { challenge: 'AQID', rpId: 'localhost', userVerification: 'required' },
    } }) })
    .mockResolvedValueOnce({ ok: true, json: async () => ({ stage: 'AUTHENTICATED' }) })
  vi.stubGlobal('fetch', fetch)

  await expect(signInWithPasskey()).resolves.toMatchObject({ stage: 'AUTHENTICATED' })
  const options = get.mock.calls[0][0] as { publicKey: PublicKeyCredentialRequestOptions }
  expect([...new Uint8Array(options.publicKey.challenge as ArrayBuffer)]).toEqual([1, 2, 3])
  expect(options.publicKey.userVerification).toBe('required')
  const sent = JSON.parse(fetch.mock.calls[1][1].body as string)
  expect(sent.credential.response.signature).toBe('DA')
  expect(sent.credential.response.userHandle).toBeNull()
})
