import type { components } from "@/lib/api/generated";
import type { Resource } from "@/features/explorer/contracts";
import { ApiError } from "@/lib/api/transport";
import { sessionRequest } from "@/features/auth/session-request";
import { validId, validSecret } from "./policy";

export type Grant = components["schemas"]["sharing_GrantService_Grant"];
export type Grants = components["schemas"]["sharing_GrantService_GrantList"];
export type Link = components["schemas"]["sharing_ShareLinkService_Link"];
export type CreatedLink =
  components["schemas"]["sharing_ShareLinkService_CreatedLink"];
export type PublicFile =
  components["schemas"]["sharing_ShareLinkService_PublicFile"];
export type Role =
  components["schemas"]["sharing_SharingController_UpdateGrant"]["role"];
export function resourcePath(entry: Resource) {
  return `${entry.resourceType === "FILE" ? "files" : "folders"}/${entry.resource.id}`;
}
export async function sharingRequest<T>(
  path: string,
  method = "GET",
  body?: unknown,
  version?: number | bigint,
  signal?: AbortSignal,
): Promise<T> {
  return sessionRequest<T>(`/api/v1/${path}`, {
    method,
    cache: "no-store",
    credentials: "same-origin",
    signal: AbortSignal.any([
      AbortSignal.timeout(30000),
      ...(signal ? [signal] : []),
    ]),
    headers: {
      "X-VaultDrive-Client": "1",
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(version === undefined
        ? {}
        : { "If-Match": `"${version.toString()}"` }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}
export function shareUrl(origin: string, created: CreatedLink) {
  const url = new URL(origin);
  if (
    url.protocol !== "https:" ||
    !validId(created.link.id) ||
    !validSecret(created.secret)
  )
    throw new Error("Share link unavailable");
  return `${url.origin}/share/${created.link.id}#${created.secret}`;
}
export function sharingError(error: unknown) {
  if (!(error instanceof ApiError) || error.status >= 500)
    return "The outcome is uncertain. Refresh access before making another change. If a link was created, its secret cannot be recovered; revoke unneeded links before creating another.";
  return (
    {
      400: "Check the registered recipient email, Viewer/Editor role, or expiry. The recipient must already have an account and cannot be the owner.",
      401: "Your session has ended. Sign in again.",
      403: "Your current permissions do not allow managing access. Only the owner can manage grants and links.",
      404: "This resource is no longer accessible, or the grant no longer exists.",
      409: "A direct grant already exists, or the resource is no longer active. Refresh access to see its current state.",
      412: "This grant changed since it was loaded. Refresh access before choosing a role again.",
      428: "A current grant version is required. Refresh access before trying again.",
      429: "Too many requests. Wait before refreshing access or trying again.",
    }[error.status] ??
    "Access could not be changed. Refresh access and try again."
  );
}
export const roleLabel = (role: string) =>
  role === "OWNER" ? "Owner" : role === "EDITOR" ? "Editor" : "Viewer";
export function expiryError(value: string, now = Date.now()) {
  if (!value) return;
  const deadline = new Date(value).getTime();
  if (
    !Number.isFinite(deadline) ||
    deadline <= now ||
    deadline > now + 7 * 86400000
  )
    return "Choose a future expiry within seven days. The server checks the final deadline.";
}
