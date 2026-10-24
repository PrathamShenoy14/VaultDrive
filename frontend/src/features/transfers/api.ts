import { readResponse } from "@/lib/api/transport";
import type { TransferApi } from "./contracts";

async function request<T>(
  path: string,
  body?: unknown,
  key?: string,
  post = false,
): Promise<T> {
  return readResponse<T>(
    await fetch(`/api/v1/${path}`, {
      method: post ? "POST" : "GET",
      credentials: "same-origin",
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(30000),
      headers: post
        ? {
            "X-VaultDrive-Client": "1",
            ...(body === undefined
              ? {}
              : { "Content-Type": "application/json" }),
            ...(key ? { "Idempotency-Key": key } : {}),
          }
        : {},
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  );
}
const sessionPath = (id: string, suffix = "") =>
  `upload-sessions/${encodeURIComponent(id)}${suffix}`;
export const transferApi: TransferApi = {
  reserve: (input, key) => request("upload-sessions", input, key, true),
  session: (id) => request(sessionPath(id)),
  initiate: (id) =>
    request(sessionPath(id, "/initiate"), undefined, undefined, true),
  sign: (id, part) =>
    request(sessionPath(id, "/part-urls"), { parts: [part] }, undefined, true),
  parts: (id, marker, limit) =>
    request(sessionPath(id, `/parts?marker=${marker}&limit=${limit}`)),
  complete: (id) =>
    request(sessionPath(id, "/complete"), undefined, undefined, true),
  recover: (id) =>
    request(sessionPath(id, "/recover"), undefined, undefined, true),
  cancel: (id) =>
    request(sessionPath(id, "/cancel"), undefined, undefined, true),
  cancellation: (id) => request(sessionPath(id, "/cancellation")),
  recoverCancellation: (id) =>
    request(sessionPath(id, "/cancel/recover"), undefined, undefined, true),
  empty: (input, key) => request("files/empty", input, key, true),
  emptyStatus: (id) =>
    request(`empty-file-creations/${encodeURIComponent(id)}`),
  recoverEmpty: (id) =>
    request(
      `empty-file-creations/${encodeURIComponent(id)}/recover`,
      undefined,
      undefined,
      true,
    ),
};
