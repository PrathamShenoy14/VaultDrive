import { ApiError, readResponse } from "@/lib/api/transport";
import { announceSession, sessionSignal } from "@/features/auth/client-session";
export async function experienceRequest<T>(
  path: string,
  signal: AbortSignal,
  body?: { displayName: string },
  method = "GET",
): Promise<T> {
  const scope = AbortSignal.any([
    signal,
    sessionSignal(),
    AbortSignal.timeout(15000),
  ]);
  try {
    const result = await readResponse<T>(
      await fetch(`/api/v1/${path}`, {
        method,
        signal: scope,
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
      }),
    );
    scope.throwIfAborted();
    return result;
  } catch (error) {
    if (!scope.aborted && error instanceof ApiError && error.status === 401) {
      announceSession("ended");
      window.dispatchEvent(new Event("vaultdrive:session-expired"));
    }
    throw error;
  }
}
