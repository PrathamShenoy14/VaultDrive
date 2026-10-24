import "server-only";
import { env } from "@/lib/env";
import { ApiError } from "@/lib/api/transport";
import { readQueryKeys, validReadQuery } from "@/features/explorer/read-policy";
import { applyIssued, backend, ensureFresh, unauthorized } from "./backend";
import {
  captureCookies,
  readSessionId,
  sessionCookie,
  sessions,
  SESSION_SECONDS,
  type Session,
} from "./session-store";

const allowed = new Map([
  ["auth/register", "POST"],
  ["auth/login", "POST"],
  ["auth/logout", "POST"],
  ["auth/refresh", "POST"],
  ["users/me", "GET"],
  ["users/me/storage", "GET"],
]);
export function failure(status: number, error: string, message: string) {
  return Response.json({ status, error, message }, { status });
}
export function noStore(response: Response) {
  response.headers.set("Cache-Control", "private, no-store, max-age=0");
  response.headers.set("Pragma", "no-cache");
  response.headers.set("Vary", "Cookie");
  return response;
}
export function sameOriginMutation(request: Request) {
  return (
    request.headers.get("Origin") === env().APP_ORIGIN &&
    request.headers.get("X-VaultDrive-Client") === "1" &&
    !["cross-site", "same-site"].includes(
      request.headers.get("Sec-Fetch-Site") ?? "",
    )
  );
}
export function forward(response: Response, cookies: string[] = []) {
  const headers = new Headers();
  for (const name of [
    "Content-Type",
    "Retry-After",
    "Date",
    "WWW-Authenticate",
    "Location",
    "ETag",
    "Allow",
  ]) {
    const value = response.headers.get(name);
    if (value) headers.set(name, value);
  }
  for (const cookie of [...cookies, ...response.headers.getSetCookie()])
    headers.append("Set-Cookie", cookie);
  return new Response(response.body, { status: response.status, headers });
}
async function boundedBody(request: Request): Promise<string | undefined> {
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 16384) {
        await reader.cancel();
        return undefined;
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}
export async function bff(request: Request, path: string): Promise<Response> {
  let result: Response;
  try {
    result = await handle(request, path);
  } catch (error) {
    result = errorResponse(error);
  }
  return noStore(result);
}
function errorResponse(error: unknown) {
  return error instanceof ApiError
    ? Response.json(error.body, {
        status: error.status,
        headers: error.retryAfter ? { "Retry-After": error.retryAfter } : {},
      })
    : failure(502, "BAD_GATEWAY", "Unable to complete the request.");
}
async function handle(request: Request, path: string) {
  const readKeys = readQueryKeys(path);
  const method = allowed.get(path) ?? (readKeys ? "GET" : undefined);
  if (!method) return failure(404, "NOT_FOUND", "Resource unavailable");
  if (request.method !== method) {
    const response = failure(405, "METHOD_NOT_ALLOWED", "Method not allowed");
    response.headers.set("Allow", method);
    return response;
  }
  const url = new URL(request.url);
  if (!validReadQuery(url.searchParams, readKeys ?? []))
    return failure(400, "BAD_REQUEST", "Query parameters are not supported");
  if (method === "POST" && !sameOriginMutation(request))
    return failure(403, "FORBIDDEN", "Permission denied");
  if (path === "auth/register" || path === "auth/login") {
    if (
      request.headers
        .get("Content-Type")
        ?.toLowerCase()
        .split(";", 1)[0]
        ?.trim() !== "application/json"
    )
      return failure(415, "UNSUPPORTED_MEDIA_TYPE", "JSON is required");
    if (Number(request.headers.get("Content-Length")) > 16384)
      return failure(413, "CONTENT_TOO_LARGE", "Request is too large");
    const body = await boundedBody(request);
    if (body === undefined)
      return failure(413, "CONTENT_TOO_LARGE", "Request is too large");
    const response = await backend(`/api/v1/${path}`, {
      method,
      body,
      headers: {
        "Content-Type": "application/json",
        "X-VaultDrive-Client": "1",
      },
    });
    if (path === "auth/register" || !response.ok) return forward(response);
    const data: Session = {
      id: "",
      access: "",
      csrf: "",
      cookies: new Map(),
      accessExpiresAt: 0,
      expiresAt: Date.now() + SESSION_SECONDS * 1000,
    };
    const cookies = await applyIssued(data, response);
    const session = sessions.create(data, env().SESSION_MAX_ENTRIES);
    if (!session)
      return failure(
        503,
        "SERVICE_UNAVAILABLE",
        "Session capacity reached. Please try later.",
      );
    // A new login replaces only this browser's local session. No credentials are serialized.
    const old = readSessionId(request.headers.get("Cookie"));
    if (old) sessions.delete(old);
    const result = new Response(null, { status: 204 });
    for (const cookie of cookies) result.headers.append("Set-Cookie", cookie);
    result.headers.append(
      "Set-Cookie",
      sessionCookie(
        session.id,
        Math.max(0, Math.floor((session.expiresAt - Date.now()) / 1000)),
      ),
    );
    return result;
  }
  const id = readSessionId(request.headers.get("Cookie"));
  if (!id) throw unauthorized();
  return sessions.locked(id, async () => {
    const session = sessions.get(id);
    if (!session) throw unauthorized();
    const cookies = await ensureFresh(session);
    if (path === "auth/refresh") {
      const result = new Response(null, { status: 204 });
      for (const cookie of cookies) result.headers.append("Set-Cookie", cookie);
      return result;
    }
    try {
      const logout = path === "auth/logout";
      const response = await backend(`/api/v1/${path}${url.search}`, {
        method,
        headers: {
          Authorization: `Bearer ${session.access}`,
          ...(logout
            ? {
                Cookie: [...session.cookies.values()].join("; "),
                "X-VaultDrive-CSRF": session.csrf,
              }
            : {}),
        },
      });
      captureCookies(session, response.headers);
      // An unexpired JWT rejected by the backend is revoked/invalid; do not refresh it.
      if (response.status === 401 || (logout && response.status === 204))
        sessions.delete(id);
      const result = forward(response, cookies);
      if (!sessions.get(id))
        result.headers.append("Set-Cookie", sessionCookie("", 0));
      return result;
    } catch (error) {
      // A successful refresh still owns new cookie headers when the following
      // read/logout fails. Keep those headers separate and preserve the error.
      const result = errorResponse(error);
      for (const cookie of cookies) result.headers.append("Set-Cookie", cookie);
      return result;
    }
  });
}
