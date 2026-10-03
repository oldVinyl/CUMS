import { csrfCookie } from "./auth-api";

export async function ledgerRequest<T>(path: string, method = "GET", body?: object, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`/api/v1/ledger${path}`, { method, credentials: "same-origin", signal,
    headers: method === "GET" ? undefined : { "Content-Type": "application/json", "X-CSRF-Token": csrfCookie() },
    body: body ? JSON.stringify(body) : undefined });
  if (!response.ok) {
    const error = await response.json().catch(() => null) as { message?: string } | null;
    throw new Error(error?.message ?? "The ledger request could not be completed.");
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>;
}
