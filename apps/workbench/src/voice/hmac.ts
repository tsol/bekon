const enc = new TextEncoder()

function toHex(bytes: ArrayBuffer | Uint8Array): string {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes)
  return [...u8].map(b => b.toString(16).padStart(2, '0')).join('')
}

/** HMAC-SHA256(SHA256(seed), seed + timestamp + body) — same as WlyaServerAdapter. */
export async function signCall(seed: string, timestamp: string, body = ''): Promise<string> {
  const keyBytes = await crypto.subtle.digest('SHA-256', enc.encode(seed))
  const key = await crypto.subtle.importKey(
    'raw',
    keyBytes,
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  )
  const sig = await crypto.subtle.sign('HMAC', key, enc.encode(seed + timestamp + body))
  return toHex(sig)
}

export async function signedCallUrl(
  baseUrl: string,
  seed: string,
  client: string,
): Promise<string> {
  const ts = Math.floor(Date.now() / 1000).toString()
  const sig = await signCall(seed, ts, '')
  const u = new URL(baseUrl.trim())
  u.searchParams.set('seed', seed)
  u.searchParams.set('client', client)
  u.searchParams.set('ts', ts)
  u.searchParams.set('sig', sig)
  return u.toString()
}
