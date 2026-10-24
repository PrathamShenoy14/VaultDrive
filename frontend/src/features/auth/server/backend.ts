import "server-only";
import { env } from "@/lib/env";
import { ApiError, readResponse, type LoginResult } from "@/lib/api/transport";
import {
  captureCookies,
  REFRESH_COOKIE,
  sessions,
  type Session,
} from "./session-store";

export async function backend(
  path: string,
  init: RequestInit = {},
  stream = false,
  deadline = 10000,
) {
  // Bound header acquisition separately: downloads may stream for the backend's
  // five-minute budget without inheriting the metadata ten-second deadline.
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), stream ? 10000 : deadline);
  try {
    return await fetch(new URL(path, env().BACKEND_URL), {
      ...init,
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.any([
        controller.signal,
        ...(init.signal ? [init.signal] : []),
        AbortSignal.timeout(stream ? 310000 : deadline),
      ]),
      headers: {
        Accept: "application/json",
        ...Object.fromEntries(new Headers(init.headers)),
      },
    });
  } catch (error) {
    const timeout = error instanceof Error && error.name === "TimeoutError";
    throw new ApiError({
      status: 502,
      error: timeout ? "UPSTREAM_TIMEOUT" : "BAD_GATEWAY",
      message: timeout
        ? "The backend request timed out. Please try again."
        : "The backend is unreachable. Please try again later.",
    });
  } finally {
    clearTimeout(timer);
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
