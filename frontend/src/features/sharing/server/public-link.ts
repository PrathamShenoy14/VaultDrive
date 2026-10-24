import "server-only";
import { env } from "@/lib/env";
import { backend } from "@/features/auth/server/backend";
import { publicLinkPath, validId, validSecret } from "../policy";
import { clearDownload, issueDownload, takeDownload } from "./download-tickets";

function secured(response: Response) {
  response.headers.set("Cache-Control", "private, no-store, max-age=0");
  response.headers.set("Referrer-Policy", "no-referrer");
  response.headers.set("X-Content-Type-Options", "nosniff");
  return response;
}
function denied(status = 404, html = false) {
  const message =
    status === 429
      ? "Share link capacity is busy. Wait before trying again."
      : status === 502
        ? "The download could not start. Try again later."
        : "This share link is unavailable. It may be invalid, expired, revoked, or no longer accessible.";
  return secured(
    html
      ? new Response(
          `<!doctype html><html lang="en"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Share link unavailable · VaultDrive</title><body><main><h1>Share link unavailable</h1><p>${message}</p><p>Return to the share page to retry. Downloads have no automatic retry or Range resume.</p></main></body></html>`,
          { status, headers: { "Content-Type": "text/html; charset=utf-8" } },
        )
      : Response.json(
          {
            status,
            error:
              status === 429
                ? "TOO_MANY_REQUESTS"
                : status === 502
                  ? "BAD_GATEWAY"
                  : "NOT_FOUND",
            message,
          },
          { status },
        ),
  );
}
function admitted(request: Request) {
  return (
    !["cross-site", "same-site"].includes(
      request.headers.get("Sec-Fetch-Site") ?? "",
    ) &&
    (!request.headers.has("Origin") ||
      request.headers.get("Origin") === env().APP_ORIGIN)
  );
}
async function forwardPublic(
  request: Request,
  path: string,
  secret: string,
  html = false,
) {
  try {
    const download = path.endsWith("/download");
    // Public capability transport is isolated from session rotation, JWTs and cookies.
    const response = await backend(
      `/api/v1/${path}`,
      {
        method: "GET",
        signal: request.signal,
        headers: {
          "X-VaultDrive-Share-Token": secret,
          ...(download ? { Accept: "*/*", "Accept-Encoding": "identity" } : {}),
        },
      },
      download,
    );
    if (!response.ok) {
      void response.body?.cancel();
      const result = denied(
        response.status === 429 ? 429 : response.status >= 500 ? 502 : 404,
        html,
      );
      if (response.status === 429)
        result.headers.set(
          "Retry-After",
          response.headers.get("Retry-After") ?? "1",
        );
      return result;
    }
    const headers = new Headers();
    for (const name of [
      "Content-Type",
      "Content-Disposition",
      "Content-Length",
    ])
      if (response.headers.has(name))
        headers.set(name, response.headers.get(name)!);
    return secured(
      new Response(response.body, { status: response.status, headers }),
    );
  } catch {
    return denied(502, html);
  }
}
export async function publicLink(request: Request, path: string) {
  const secret = request.headers.get("X-VaultDrive-Share-Token") ?? "";
  if (
    request.method !== "GET" ||
    request.url.includes("?") ||
    !publicLinkPath(path) ||
    !validSecret(secret) ||
    !admitted(request)
  )
    return denied();
  return forwardPublic(request, path, secret);
}
// No-referrer makes native POST Origin null. Prepare with an exact-origin fetch,
// then hand a one-use HttpOnly ticket to native GET navigation for streaming.
export async function publicDownload(request: Request, linkId: string) {
  if (!validId(linkId) || request.url.includes("?") || !admitted(request))
    return denied(404, true);
  if (request.method === "GET") {
    const secret = takeDownload(request, linkId);
    const response = secret
      ? await forwardPublic(
          request,
          `public/share-links/${linkId}/download`,
          secret,
          true,
        )
      : denied(404, true);
    response.headers.append("Set-Cookie", clearDownload());
    return response;
  }
  if (
    request.method !== "POST" ||
    request.headers.get("Origin") !== env().APP_ORIGIN ||
    request.headers.get("X-VaultDrive-Client") !== "1" ||
    request.headers.get("Content-Type")?.split(";", 1)[0] !== "application/json"
  )
    return denied();
  const reader = request.body?.getReader();
  if (!reader) return denied(404, true);
  let body = "";
  const decoder = new TextDecoder();
  try {
    let size = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 1024) {
        await reader.cancel();
        return denied(404, true);
      }
      body += decoder.decode(value, { stream: true });
    }
    body += decoder.decode();
  } catch {
    return denied(404, true);
  } finally {
    reader.releaseLock();
  }
  let secret: string;
  try {
    const data = JSON.parse(body);
    if (
      Object.keys(data).length !== 1 ||
      typeof data.secret !== "string" ||
      !validSecret(data.secret)
    )
      return denied();
    secret = data.secret;
  } catch {
    return denied();
  }
  const cookie = issueDownload(request, linkId, secret);
  if (!cookie)
    return secured(
      Response.json(
        {
          status: 503,
          error: "SERVICE_UNAVAILABLE",
          message: "Download capacity is busy. Wait and try again.",
        },
        { status: 503, headers: { "Retry-After": "1" } },
      ),
    );
  return secured(
    new Response(null, { status: 204, headers: { "Set-Cookie": cookie } }),
  );
}
