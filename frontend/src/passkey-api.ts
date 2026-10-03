import { csrfCookie } from './auth-api'
import type { AuthStep } from './auth-api'

type Ceremony = { token: string; options: Record<string, unknown> }
type CreationJson = Omit<PublicKeyCredentialCreationOptions, 'challenge' | 'user' | 'excludeCredentials'> & {
  challenge: string; user: Omit<PublicKeyCredentialUserEntity, 'id'> & { id: string };
  excludeCredentials?: Array<Omit<PublicKeyCredentialDescriptor, 'id'> & { id: string }>
}
type RequestJson = Omit<PublicKeyCredentialRequestOptions, 'challenge' | 'allowCredentials'> & {
  challenge: string; allowCredentials?: Array<Omit<PublicKeyCredentialDescriptor, 'id'> & { id: string }>
}

function decode(value: string): ArrayBuffer {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/')
  const binary = atob(padded)
  return Uint8Array.from(binary, character => character.charCodeAt(0)).buffer
}

function encode(value: ArrayBuffer | null): string | null {
  if (!value) return null
  const bytes = new Uint8Array(value)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}

async function post(path: string, body: object): Promise<unknown> {
  const response = await fetch(`/api/v1/auth/passkey/${path}`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfCookie() },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    const error = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(error?.message ?? 'Passkey could not be verified. Please try again.')
  }
  return response.json()
}

function available() {
  if (!window.PublicKeyCredential || !navigator.credentials) {
    throw new Error('Passkeys are unavailable in this browser or context.')
  }
}

export async function registerPasskey(challenge?: string): Promise<AuthStep> {
  available()
  const ceremony = await post('register/options', { challenge }) as Ceremony
  const options = ceremony.options.publicKey as CreationJson
  const publicKey = { ...options,
    challenge: decode(options.challenge),
    user: { ...options.user, id: decode(options.user.id) },
    excludeCredentials: options.excludeCredentials?.map(item => ({ ...item, id: decode(item.id) })),
  } as PublicKeyCredentialCreationOptions
  const credential = await navigator.credentials.create({ publicKey }) as PublicKeyCredential | null
  if (!credential) throw new Error('Passkey creation was cancelled.')
  const response = credential.response as AuthenticatorAttestationResponse
  return post('register/finish', { challenge, token: ceremony.token, credential: {
    id: credential.id, rawId: encode(credential.rawId), type: credential.type,
    response: { attestationObject: encode(response.attestationObject),
      clientDataJSON: encode(response.clientDataJSON), transports: response.getTransports?.() ?? [] },
    clientExtensionResults: credential.getClientExtensionResults(),
    authenticatorAttachment: credential.authenticatorAttachment,
  } }) as Promise<AuthStep>
}

export async function passkeyProof(): Promise<{ token: string; credential: object }> {
  available()
  const ceremony = await post('login/options', {}) as Ceremony
  const options = ceremony.options.publicKey as RequestJson
  const publicKey = { ...options, challenge: decode(options.challenge),
    allowCredentials: options.allowCredentials?.map(item => ({ ...item, id: decode(item.id) })),
  } as PublicKeyCredentialRequestOptions
  const credential = await navigator.credentials.get({ publicKey }) as PublicKeyCredential | null
  if (!credential) throw new Error('Passkey sign-in was cancelled.')
  const response = credential.response as AuthenticatorAssertionResponse
  return { token: ceremony.token, credential: {
    id: credential.id, rawId: encode(credential.rawId), type: credential.type,
    response: { authenticatorData: encode(response.authenticatorData),
      clientDataJSON: encode(response.clientDataJSON), signature: encode(response.signature),
      userHandle: encode(response.userHandle) },
    clientExtensionResults: credential.getClientExtensionResults(),
    authenticatorAttachment: credential.authenticatorAttachment,
  } }
}

export async function signInWithPasskey(): Promise<AuthStep> {
  return post("login/finish", await passkeyProof()) as Promise<AuthStep>;
}
