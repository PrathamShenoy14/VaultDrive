import "server-only";
import { randomBytes } from "node:crypto";

export const SESSION_COOKIE = "__Host-vaultdrive-session";
export const REFRESH_COOKIE = "__Host-vaultdrive-refresh";
export const SESSION_SECONDS = 7 * 24 * 60 * 60;
export interface Session {
  id: string;
  access: string;
  csrf: string;
  cookies: Map<string, string>;
  accessExpiresAt: number;
  expiresAt: number;
}
// One process is an explicit deployment boundary. Global state also survives dev
// module reloads. Never persist credentials to disk or expose this module to clients.
class SessionStore {
  private sessions = new Map<string, Session>();
  private tails = new Map<string, Promise<void>>();
  get(id?: string) {
    const session = id ? this.sessions.get(id) : undefined;
    if (session && session.expiresAt <= Date.now()) {
      this.delete(session.id);
      return undefined;
    }
    return session;
  }
  create(data: Omit<Session, "id">, capacity: number) {
    for (const session of this.sessions.values())
      if (session.expiresAt <= Date.now()) this.delete(session.id);
    if (this.sessions.size >= capacity) return undefined;
    const session = { ...data, id: randomBytes(32).toString("base64url") };
    this.sessions.set(session.id, session);
    return session;
  }
  delete(id: string) {
    this.sessions.delete(id);
  }
  async locked<T>(id: string, action: () => Promise<T>): Promise<T> {
    const previous = this.tails.get(id) ?? Promise.resolve();
    let release!: () => void;
    const next = new Promise<void>((resolve) => {
      release = resolve;
    });
    this.tails.set(id, next);
    await previous;
    try {
      return await action();
    } finally {
      release();
      if (this.tails.get(id) === next) this.tails.delete(id);
    }
  }
}
const globals = globalThis as typeof globalThis & {
  vaultdriveSessions?: SessionStore;
};
export const sessions = (globals.vaultdriveSessions ??= new SessionStore());
export function sessionCookie(id: string, maxAge: number) {
  return `${SESSION_COOKIE}=${id}; Path=/; Max-Age=${maxAge}; Secure; HttpOnly; SameSite=Strict`;
}
export function captureCookies(
  session: Pick<Session, "cookies">,
  headers: Headers,
) {
  // Never split on comma: Expires attributes contain commas. Keep each header.
  const cookies = headers.getSetCookie();
  for (const cookie of cookies) {
    const pair = cookie.split(";", 1)[0]!;
    const index = pair.indexOf("=");
    if (index > 0) {
      const name = pair.slice(0, index);
      if (/;\s*Max-Age=0(?:;|$)/i.test(cookie)) session.cookies.delete(name);
      else session.cookies.set(name, pair);
    }
  }
  return cookies;
}
export function readSessionId(cookie: string | null) {
  const values = (cookie ?? "")
    .split(";")
    .map((v) => v.trim())
    .filter((v) => v.startsWith(`${SESSION_COOKIE}=`));
  if (values.length !== 1) return undefined;
  const id = values[0]!.slice(SESSION_COOKIE.length + 1);
  return /^[A-Za-z0-9_-]{43}$/.test(id) ? id : undefined;
}
