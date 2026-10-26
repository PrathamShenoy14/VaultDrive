import { sessionRequest } from "@/features/auth/session-request";
export async function experienceRequest<T>(
  path: string,
  signal: AbortSignal,
  body?: { displayName: string },
  method = "GET",
): Promise<T> {
  return sessionRequest<T>(`/api/v1/${path}`, {
    method,
    signal: AbortSignal.any([signal, AbortSignal.timeout(15000)]),
    cache: "no-store",
    credentials: "same-origin",
    headers:
      method === "GET"
        ? {}
        : {
            "X-VaultDrive-Client": "1",
            ...(body ? { "Content-Type": "application/json" } : {}),
          },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
}
