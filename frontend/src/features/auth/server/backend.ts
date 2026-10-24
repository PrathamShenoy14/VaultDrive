import "server-only";
import { env } from "@/lib/env";
import { ApiError, readResponse, type LoginResult } from "@/lib/api/transport";
import {
  captureCookies,
  REFRESH_COOKIE,
  sessions,
  type Session,
} from "./session-store";

export async function backend(path: string, init: RequestInit = {}) {
  try {
    return await fetch(new URL(path, env().BACKEND_URL), {
      ...init,
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.timeout(10000),
      headers: {
        Accept: "application/json",
        ...Object.fromEntries(new Headers(init.headers)),
      },
    });
  } catch {
    throw new ApiError({
      status: 502,
      error: "BAD_GATEWAY",
      message: "The backend is unreachable. Please try again later.",
    });
  }
}
export function unauthorized() {
  return new ApiError({
    status: 401,
    error: "UNAUTHORIZED",
    message: "Authentication required",
  });
}
export function expired(session: Session) {
  return session.accessExpiresAt <= Date.now() + 15000;
}
export async function applyIssued(session: Session, response: Response) {
  const issued = await readResponse<LoginResult>(response);
  const csrf = response.headers.get("X-VaultDrive-CSRF");
  const cookies = captureCookies(session, response.headers);
  const cookie = cookies.find((v) => v.startsWith(`${REFRESH_COOKIE}=`));
  const maxAge = Number(cookie?.match(/;\s*Max-Age=(\d+)/i)?.[1]);
  const accessSeconds = Number(issued.expiresIn);
  if (
    typeof issued.accessToken !== "string" ||
    !issued.accessToken ||
    issued.tokenType !== "Bearer" ||
    !Number.isSafeInteger(accessSeconds) ||
    accessSeconds <= 0 ||
    !csrf ||
    !/^[A-Za-z0-9_-]{43}$/.test(csrf) ||
    !Number.isSafeInteger(maxAge) ||
    maxAge <= 0 ||
    cookies.filter((v) => v.startsWith(`${REFRESH_COOKIE}=`)).length !== 1 ||
    !new RegExp(`^${REFRESH_COOKIE}=[A-Za-z0-9_-]{43};`).test(cookie ?? "")
  ) {
    throw new ApiError({
      status: 502,
      error: "BAD_GATEWAY",
      message: "Authentication response was incomplete. Please sign in again.",
    });
  }
  session.access = issued.accessToken;
  session.csrf = csrf;
  session.accessExpiresAt = Date.now() + accessSeconds * 1000;
  session.expiresAt = Math.min(session.expiresAt, Date.now() + maxAge * 1000);
  return cookies;
}
// Call only under the per-session lock, and only in a route that can forward cookies.
export async function ensureFresh(session: Session) {
  if (!expired(session)) return [];
  try {
    const response = await backend("/api/v1/auth/refresh", {
      method: "POST",
      headers: {
        Cookie: [...session.cookies.values()].join("; "),
        "X-VaultDrive-CSRF": session.csrf,
      },
    });
    return await applyIssued(session, response);
  } catch (error) {
    // 429 is known admission-before-business-work. Any other uncertain rotation
    // destroys local credentials; retrying an old token can revoke the family.
    if (!(error instanceof ApiError && error.status === 429))
      sessions.delete(session.id);
    throw error;
  }
}
