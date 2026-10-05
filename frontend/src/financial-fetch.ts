// Keep ambiguous retries bound to one key without storing form data or keys on disk.
const retryKeys = new Map<string, string>();
export async function financialFetch(url: string, init: RequestInit = {}): Promise<Response> {
  if (
    (init.method ?? "GET").toUpperCase() !== "POST" ||
    (init.body && typeof init.body !== "string")
  )
    return fetch(url, init);
  const headers = new Headers(init.headers);
  if (headers.has("Idempotency-Key")) return fetch(url, init);
  const bytes = new TextEncoder().encode(`${url}\n${init.body ?? ""}`);
  const hash = [...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))]
    .map((n) => n.toString(16).padStart(2, "0"))
    .join("");
  let key = retryKeys.get(hash);
  if (!key) {
    key = crypto.randomUUID();
    retryKeys.set(hash, key);
  }
  headers.set("Idempotency-Key", key);
  const response = await fetch(url, { ...init, headers });
  if (
    response.ok ||
    (response.status >= 400 && response.status < 500 && ![408, 429].includes(response.status))
  )
    retryKeys.delete(hash);
  return response;
}
