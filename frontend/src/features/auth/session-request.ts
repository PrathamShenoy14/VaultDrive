import { ApiError, readResponse } from "@/lib/api/transport";
import { announceSession, sessionSignal } from "./client-session";

// Authenticated browser traffic shares one lifetime across every milestone.
// Aborting a mutation leaves its outcome uncertain; this never retries it.
export async function sessionRequest<T>(
  path: string,
  init: RequestInit,
): Promise<T> {
  const signal = AbortSignal.any([
    sessionSignal(),
    ...(init.signal ? [init.signal] : []),
  ]);
  signal.throwIfAborted();
  try {
    const value = await readResponse<T>(await fetch(path, { ...init, signal }));
    signal.throwIfAborted();
    return value;
  } catch (error) {
    if (!signal.aborted && error instanceof ApiError && error.status === 401) {
      announceSession("ended");
      if (typeof window !== "undefined")
        window.dispatchEvent(new Event("vaultdrive:session-expired"));
    }
    throw error;
  }
}
