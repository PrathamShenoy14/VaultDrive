import "server-only";
import { randomBytes } from "node:crypto";

const cookieName = "__Host-vaultdrive-share-download";
const lifetime = 60000;
const capacity = 256;
type Ticket = { linkId: string; secret: string; expiresAt: number };
// Bounded, process-local, one-use handoffs. No URL or browser-readable credential.
const tickets = new Map<string, Ticket>();
function cookieId(request: Request) {
  const values = (request.headers.get("Cookie") ?? "")
    .split(";")
    .map((value) => value.trim())
    .filter((value) => value.startsWith(`${cookieName}=`));
  if (values.length !== 1) return;
  const id = values[0]!.slice(cookieName.length + 1);
  return /^[A-Za-z0-9_-]{43}$/.test(id) ? id : undefined;
}
function prune(now: number) {
  for (const [id, ticket] of tickets)
    if (ticket.expiresAt <= now) tickets.delete(id);
}
export function issueDownload(
  request: Request,
  linkId: string,
  secret: string,
  now = Date.now(),
) {
  prune(now);
  const previous = cookieId(request);
  if (previous) tickets.delete(previous);
  if (tickets.size >= capacity) return;
  const id = randomBytes(32).toString("base64url");
  tickets.set(id, { linkId, secret, expiresAt: now + lifetime });
  setTimeout(() => tickets.delete(id), lifetime).unref();
  return `${cookieName}=${id}; Max-Age=60; Path=/; Secure; HttpOnly; SameSite=Strict`;
}
export function takeDownload(
  request: Request,
  linkId: string,
  now = Date.now(),
) {
  prune(now);
  const id = cookieId(request);
  if (!id) return;
  const ticket = tickets.get(id);
  // A mismatched route cannot steal/consume another tab's pending handoff.
  if (!ticket || ticket.linkId !== linkId) return;
  tickets.delete(id);
  return ticket.secret;
}
export const clearDownload = () =>
  `${cookieName}=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Strict`;
