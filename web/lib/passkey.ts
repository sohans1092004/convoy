import { api, ApiError, call } from './api';

// WebAuthn ceremonies against identity E003-E005/E151. Binary values travel as base64url (no padding).
const toB64 = (b: ArrayBuffer) => btoa(String.fromCharCode(...new Uint8Array(b))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const fromB64 = (s: string) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));

// The browser prompt was cancelled, timed out or has no passkey for this site: say so, not "network error".
async function prompt<T>(f: () => Promise<T>): Promise<T> {
  try {
    return await f();
  } catch (e) {
    if (e instanceof ApiError) throw e;
    throw new ApiError({ status: 0, title: 'Passkey not used', detail: 'The passkey prompt was cancelled or this device has no Convoy passkey. You can sign in with an email code.' });
  }
}

export const passkeysSupported = () => typeof window !== 'undefined' && !!window.PublicKeyCredential;

/** Adds a passkey to the signed-in account. */
export async function registerPasskey() {
  const { data: o } = await call(api.POST('/auth/passkeys/register/options'));
  const cred = (await prompt(() => navigator.credentials.create({
    publicKey: {
      challenge: fromB64(o.challenge), rp: { id: o.rp_id, name: o.rp_name },
      user: { id: fromB64(o.user_handle), name: o.user_name, displayName: o.user_name },
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
      authenticatorSelection: { residentKey: 'required', userVerification: 'required' },
      excludeCredentials: o.exclude_credentials.map((id) => ({ type: 'public-key' as const, id: fromB64(id) })),
      attestation: 'none', timeout: o.timeout_ms,
    },
  }))) as PublicKeyCredential;
  const r = cred.response as AuthenticatorAttestationResponse;
  return call(api.POST('/auth/passkeys/register', { body: {
    challenge_id: o.challenge_id, client_data_json: toB64(r.clientDataJSON), attestation_object: toB64(r.attestationObject), transports: r.getTransports?.() ?? [],
  } }));
}

/** Signs in with any passkey registered for this site (no email needed). */
export async function signInWithPasskey() {
  const { data: o } = await call(api.POST('/auth/passkeys/authenticate/options'));
  const cred = (await prompt(() => navigator.credentials.get({
    publicKey: { challenge: fromB64(o.challenge), rpId: o.rp_id, userVerification: 'required', timeout: o.timeout_ms },
  }))) as PublicKeyCredential;
  const r = cred.response as AuthenticatorAssertionResponse;
  return call(api.POST('/auth/passkeys/authenticate', { body: {
    challenge_id: o.challenge_id, credential_id: toB64(cred.rawId), client_data_json: toB64(r.clientDataJSON),
    authenticator_data: toB64(r.authenticatorData), signature: toB64(r.signature), user_handle: r.userHandle ? toB64(r.userHandle) : null,
  } }));
}
