// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from "vitest";
import { bff, forward } from "@/features/auth/server/bff";
import {
  sessions,
  sessionCookie,
  REFRESH_COOKIE,
  SESSION_COOKIE,
  readSessionId,
} from "@/features/auth/server/session-store";
const csrf = "c".repeat(43);
const refresh = "r".repeat(43);
function issued() {
  return new Response(
    JSON.stringify({
      accessToken: "real-backend-token-placeholder-in-isolated-test",
      tokenType: "Bearer",
      expiresIn: 1800,
    }),
    {
      headers: {
        "Content-Type": "application/json",
        "X-VaultDrive-CSRF": csrf,
        "Set-Cookie": `${REFRESH_COOKIE}=${refresh}; Max-Age=600000; Path=/; Secure; HttpOnly; SameSite=Strict`,
      },
    },
  );
}
function fixture(expired = false) {
  return sessions.create(
    {
      access: "access",
      csrf,
      cookies: new Map([[REFRESH_COOKIE, `${REFRESH_COOKIE}=original`]]),
      accessExpiresAt: Date.now() + (expired ? -1000 : 1800000),
      expiresAt: Date.now() + 604800000,
    },
    10000,
  )!;
}
function request(path: string, id?: string, extra: RequestInit = {}) {
  const method = path.startsWith("auth/") ? "POST" : "GET";
  const headers = new Headers({
    Origin: "https://localhost:3000",
    "X-VaultDrive-Client": "1",
    ...(id ? { Cookie: sessionCookie(id, 604800).split(";")[0]! } : {}),
  });
  new Headers(extra.headers).forEach((value, key) => headers.set(key, value));
  return new Request(`https://localhost:3000/api/v1/${path}`, {
    method,
    ...extra,
    headers,
  });
}
beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn());
});
describe("BFF auth and cookie boundary (isolated backend fetch)", () => {
  it("forwards committed rotation cookies even when the following request loses its response", async () => {
    const session = fixture(true);
    vi.mocked(fetch).mockImplementation(async (url) => {
      if (String(url).endsWith("/refresh")) return issued();
      throw new Error("read connection lost");
    });
    const result = await bff(request("users/me", session.id), "users/me");
    expect(result.status).toBe(502);
    expect(result.headers.getSetCookie()).toHaveLength(1);
    expect(sessions.get(session.id)?.cookies.get(REFRESH_COOKIE)).toContain(
      refresh,
    );
    sessions.delete(session.id);
  });
  it("bounds chunked login bodies before forwarding them", async () => {
    const result = await bff(
      request("auth/login", undefined, {
        headers: { "Content-Type": "application/json" },
        body: "x".repeat(16385),
      }),
      "auth/login",
    );
    expect(result.status).toBe(413);
    expect(fetch).not.toHaveBeenCalled();
  });
  it("serializes logout after an in-flight refresh and rejects later reads", async () => {
    const session = fixture(true);
    vi.mocked(fetch).mockImplementation(async (url) => {
      if (String(url).endsWith("/refresh")) {
        await new Promise((resolve) => setTimeout(resolve, 20));
        return issued();
      }
      if (String(url).endsWith("/logout"))
        return new Response(null, { status: 204 });
      return Response.json({ displayName: "Alice" });
    });
    const results = await Promise.all([
      bff(request("users/me", session.id), "users/me"),
      bff(request("auth/logout", session.id), "auth/logout"),
      bff(request("users/me", session.id), "users/me"),
    ]);
    expect(results.map((r) => r.status)).toEqual([200, 204, 401]);
    expect(fetch).toHaveBeenCalledTimes(3);
  });
  it("registers without logging in", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response('{"userId":"id","message":"User registered successfully"}', {
        status: 201,
      }),
    );
    const result = await bff(
      request("auth/register", undefined, {
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          email: "a@example.test",
          password: "Password12345!",
          displayName: "A",
        }),
      }),
      "auth/register",
    );
    expect(result.status).toBe(201);
    expect(result.headers.getSetCookie()).toEqual([]);
  });
  it("login holds credentials server-side and forwards multiple cookies separately", async () => {
    const upstream = issued();
    upstream.headers.append(
      "Set-Cookie",
      "another=value; Path=/; Expires=Wed, 01 Nov 2028 00:00:00 GMT; Secure; HttpOnly",
    );
    vi.mocked(fetch).mockResolvedValue(upstream);
    const result = await bff(
      request("auth/login", undefined, {
        headers: { "Content-Type": "application/json" },
        body: '{"email":"a@example.test","password":"secret"}',
      }),
      "auth/login",
    );
    expect(result.status).toBe(204);
    expect(await result.text()).toBe("");
    expect(result.headers.has("X-VaultDrive-CSRF")).toBe(false);
    const cookies = result.headers.getSetCookie();
    expect(cookies).toHaveLength(3);
    expect(cookies[1]).toContain("Expires=Wed, 01 Nov");
    const id = readSessionId(
      cookies.find((v) => v.startsWith(SESSION_COOKIE))!.split(";")[0]!,
    );
    expect(sessions.get(id)?.csrf).toBe(csrf);
    expect(sessions.get(id)?.cookies.get(REFRESH_COOKIE)).toContain(refresh);
    expect(cookies[2]).toContain("Secure; HttpOnly; SameSite=Strict");
    expect(
      new Headers(vi.mocked(fetch).mock.calls[0]![1]?.headers).get(
        "X-VaultDrive-Client",
      ),
    ).toBe("1");
    sessions.delete(id!);
  });
  it("restores a session from its opaque cookie and never forwards browser credentials", async () => {
    const session = fixture();
    vi.mocked(fetch).mockResolvedValue(new Response('{"displayName":"Alice"}'));
    const result = await bff(
      request("users/me", session.id, {
        headers: {
          Authorization: "Bearer attacker",
          Cookie: `${SESSION_COOKIE}=${session.id}; foreign=bad`,
        },
      }),
      "users/me",
    );
    expect(result.status).toBe(200);
    expect(
      new Headers(vi.mocked(fetch).mock.calls[0]![1]?.headers).get(
        "Authorization",
      ),
    ).toBe("Bearer access");
    expect(
      new Headers(vi.mocked(fetch).mock.calls[0]![1]?.headers).get("Cookie"),
    ).toBeNull();
    expect(result.headers.get("Cache-Control")).toContain("no-store");
    sessions.delete(session.id);
  });
  it("serializes concurrent callers across a shared session and rotates once", async () => {
    const session = fixture(true);
    const originalExpiry = session.expiresAt;
    vi.mocked(fetch).mockImplementation(async (url, init) => {
      if (String(url).endsWith("/refresh")) {
        expect(new Headers(init?.headers).get("Authorization")).toBeNull();
        expect(new Headers(init?.headers).get("X-VaultDrive-CSRF")).toBe(csrf);
        expect(new Headers(init?.headers).get("Cookie")).toContain("original");
        await new Promise((resolve) => setTimeout(resolve, 20));
        return issued();
      }
      return new Response('{"displayName":"Alice"}');
    });
    const results = await Promise.all(
      Array.from({ length: 12 }, () =>
        bff(request("users/me", session.id), "users/me"),
      ),
    );
    expect(results.every((v) => v.status === 200)).toBe(true);
    expect(
      vi
        .mocked(fetch)
        .mock.calls.filter(([url]) => String(url).endsWith("/refresh")),
    ).toHaveLength(1);
    expect(session.expiresAt).toBeLessThanOrEqual(originalExpiry);
    expect(results.flatMap((v) => v.headers.getSetCookie())).toHaveLength(1);
    sessions.delete(session.id);
  });
  it("does not rotate on duplicate explicit refresh requests while the access token is fresh", async () => {
    const session = fixture(true);
    vi.mocked(fetch).mockImplementation(async () => issued());
    const results = await Promise.all([
      bff(request("auth/refresh", session.id), "auth/refresh"),
      bff(request("auth/refresh", session.id), "auth/refresh"),
    ]);
    expect(results.map((r) => r.status)).toEqual([204, 204]);
    expect(fetch).toHaveBeenCalledTimes(1);
    sessions.delete(session.id);
  });
  it.each([401, 403, 500, 503])(
    "abandons %i refresh failures without retrying consumed credentials",
    async (status) => {
      const session = fixture(true);
      vi.mocked(fetch).mockResolvedValue(
        Response.json(
          { status, error: "FAILURE", message: "Failure" },
          { status },
        ),
      );
      expect(
        (await bff(request("auth/refresh", session.id), "auth/refresh")).status,
      ).toBe(status);
      expect(
        (await bff(request("auth/refresh", session.id), "auth/refresh")).status,
      ).toBe(401);
      expect(fetch).toHaveBeenCalledTimes(1);
      expect(sessions.get(session.id)).toBeUndefined();
    },
  );
  it("abandons an uncertain network refresh", async () => {
    const session = fixture(true);
    vi.mocked(fetch).mockRejectedValue(new Error("connection lost"));
    expect(
      (await bff(request("auth/refresh", session.id), "auth/refresh")).status,
    ).toBe(502);
    expect(sessions.get(session.id)).toBeUndefined();
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it("retains a 429 session and forwards Retry-After without an automatic retry", async () => {
    const session = fixture(true);
    vi.mocked(fetch).mockResolvedValue(
      Response.json(
        {
          status: 429,
          error: "TOO_MANY_REQUESTS",
          message: "Capacity exceeded",
        },
        { status: 429, headers: { "Retry-After": "1" } },
      ),
    );
    const result = await bff(
      request("auth/refresh", session.id),
      "auth/refresh",
    );
    expect(result.headers.get("Retry-After")).toBe("1");
    expect(sessions.get(session.id)).toBe(session);
    expect(fetch).toHaveBeenCalledTimes(1);
    sessions.delete(session.id);
  });
  it("revoked access is rejected without trying refresh", async () => {
    const session = fixture();
    vi.mocked(fetch).mockResolvedValue(
      Response.json(
        {
          status: 401,
          error: "UNAUTHORIZED",
          message: "Authentication required",
        },
        { status: 401 },
      ),
    );
    expect(
      (await bff(request("users/me", session.id), "users/me")).status,
    ).toBe(401);
    expect(sessions.get(session.id)).toBeUndefined();
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it("refreshes expired access once before logout and forwards the newest cookie/CSRF/bearer", async () => {
    const session = fixture(true);
    vi.mocked(fetch).mockImplementation(async (url, init) => {
      if (String(url).endsWith("/refresh")) return issued();
      const headers = new Headers(init?.headers);
      expect(headers.get("Cookie")).toContain(refresh);
      expect(headers.get("X-VaultDrive-CSRF")).toBe(csrf);
      expect(headers.get("Authorization")).toContain(
        "real-backend-token-placeholder",
      );
      return new Response(null, {
        status: 204,
        headers: {
          "Set-Cookie": `${REFRESH_COOKIE}=; Max-Age=0; Secure; HttpOnly; Path=/; SameSite=Strict`,
        },
      });
    });
    const result = await bff(request("auth/logout", session.id), "auth/logout");
    expect(result.status).toBe(204);
    expect(sessions.get(session.id)).toBeUndefined();
    expect(result.headers.getSetCookie()).toHaveLength(3);
    expect(
      (await bff(request("users/me", session.id), "users/me")).status,
    ).toBe(401);
    expect(fetch).toHaveBeenCalledTimes(2);
  });
  it("does not clear backend cookies on failed logout", async () => {
    const session = fixture();
    vi.mocked(fetch).mockResolvedValue(
      Response.json(
        { status: 403, error: "FORBIDDEN", message: "Invalid CSRF token" },
        { status: 403 },
      ),
    );
    const result = await bff(request("auth/logout", session.id), "auth/logout");
    expect(result.status).toBe(403);
    expect(result.headers.getSetCookie()).toEqual([]);
    sessions.delete(session.id);
  });
  it("expires absolute sessions without backend dispatch", async () => {
    const session = fixture();
    session.expiresAt = Date.now() - 1;
    expect(
      (await bff(request("users/me", session.id), "users/me")).status,
    ).toBe(401);
    expect(fetch).not.toHaveBeenCalled();
  });
  it.each<Record<string, string>>([
    {},
    { Origin: "https://evil.test" },
    { Origin: "https://localhost:3000", "X-VaultDrive-Client": "2" },
  ])(
    "blocks absent/wrong origin or custom-header admission",
    async (headers) => {
      const req = new Request("https://localhost:3000/api/v1/auth/login", {
        method: "POST",
        headers,
      });
      expect((await bff(req, "auth/login")).status).toBe(403);
      expect(fetch).not.toHaveBeenCalled();
    },
  );
  it("rejects query auth, unknown operations and unsupported methods", async () => {
    expect(
      (
        await bff(
          new Request("https://localhost:3000/api/v1/auth/login?secret=x", {
            method: "POST",
          }),
          "auth/login",
        )
      ).status,
    ).toBe(400);
    expect((await bff(request("files"), "files")).status).toBe(404);
    expect(
      (
        await bff(
          request("users/me", undefined, { method: "POST" }),
          "users/me",
        )
      ).status,
    ).toBe(405);
    expect(fetch).not.toHaveBeenCalled();
  });
  it("forwards multiple Set-Cookie values without comma splitting", () => {
    const response = new Response(null);
    response.headers.append(
      "Set-Cookie",
      "a=1; Expires=Wed, 01 Nov 2028 00:00:00 GMT",
    );
    response.headers.append("Set-Cookie", "b=2; HttpOnly");
    expect(forward(response).headers.getSetCookie()).toHaveLength(2);
  });
});
