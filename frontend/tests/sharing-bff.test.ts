// @vitest-environment node
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { bff } from "@/features/auth/server/bff";
import { publicDownload } from "@/features/sharing/server/public-link";
import {
  sessions,
  sessionCookie,
  REFRESH_COOKIE,
} from "@/features/auth/server/session-store";
const id = "11111111-1111-4111-8111-111111111111";
const secret = "A".repeat(43);
let sessionId: string;
const routes: [string, string][] = [
  ...["files", "folders"].flatMap(
    (kind) =>
      [
        [`${kind}/${id}/grants`, "GET"],
        [`${kind}/${id}/grants`, "POST"],
        [`${kind}/${id}/grants/${id}`, "PATCH"],
        [`${kind}/${id}/grants/${id}`, "DELETE"],
      ] as [string, string][],
  ),
  [`files/${id}/share-links`, "GET"],
  [`files/${id}/share-links`, "POST"],
  [`files/${id}/share-links/${id}`, "DELETE"],
];
function request(
  path: string,
  method: string,
  headers: Record<string, string> = {},
) {
  return new Request(`https://localhost:3000/api/v1/${path}`, {
    method,
    headers: {
      Origin: "https://localhost:3000",
      "X-VaultDrive-Client": "1",
      Cookie: sessionCookie(sessionId, 1000).split(";")[0]!,
      Authorization: "browser-jwt",
      "X-VaultDrive-CSRF": "browser-csrf",
      "X-VaultDrive-Share-Token": "browser-secret",
      "If-Match": '"9007199254740993"',
      ...(method === "POST" || method === "PATCH"
        ? { "Content-Type": "application/json" }
        : {}),
      ...headers,
    },
    ...(method === "POST" || method === "PATCH"
      ? { body: '{"role":"EDITOR"}' }
      : {}),
  });
}
beforeEach(() => {
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockImplementation(() =>
        Promise.resolve(
          Response.json(
            { ok: true },
            { headers: { ETag: '"9007199254740994"' } },
          ),
        ),
      ),
  );
  sessionId = sessions.create(
    {
      access: "server-jwt",
      csrf: "server-csrf",
      cookies: new Map(),
      accessExpiresAt: Date.now() + 1800000,
      expiresAt: Date.now() + 604800000,
    },
    10000,
  )!.id;
});
afterEach(() => sessions.delete(sessionId));
it.each(routes)(
  "forwards sharing %s %s exactly once with server authentication",
  async (path, method) => {
    const response = await bff(request(path, method), path);
    expect(response.status).toBe(200);
    expect(response.headers.get("Cache-Control")).toContain("no-store");
    expect(response.headers.get("ETag")).toBe('"9007199254740994"');
    expect(fetch).toHaveBeenCalledTimes(1);
    const [url, init] = vi.mocked(fetch).mock.calls[0]!;
    expect(String(url)).toBe(`http://127.0.0.1:8080/api/v1/${path}`);
    expect(init!.method).toBe(method);
    const headers = new Headers(init!.headers);
    expect(headers.get("Authorization")).toBe("Bearer server-jwt");
    for (const header of [
      "Cookie",
      "X-VaultDrive-CSRF",
      "X-VaultDrive-Share-Token",
    ])
      expect(headers.has(header)).toBe(false);
    expect(headers.get("If-Match")).toBe(
      method === "PATCH" ? '"9007199254740993"' : null,
    );
  },
);
it.each(routes.filter(([, method]) => method !== "GET"))(
  "enforces exact origin, CSRF admission and session on %s %s",
  async (path, method) => {
    for (const headers of [
      { Origin: "https://foreign.test" },
      { Origin: "http://localhost:3000" },
      { Origin: "" },
      { "X-VaultDrive-Client": "" },
      { "Sec-Fetch-Site": "same-site" },
      { "Sec-Fetch-Site": "cross-site" },
    ] as Record<string, string>[])
      expect((await bff(request(path, method, headers), path)).status).toBe(
        403,
      );
    sessions.delete(sessionId);
    expect((await bff(request(path, method), path)).status).toBe(401);
    expect(fetch).not.toHaveBeenCalled();
  },
);
it("requires an exact quoted int64 precondition, never rounded or wildcard versions", async () => {
  const path = `files/${id}/grants/${id}`;
  for (const [value, status] of [
    ["", 428],
    ["12", 400],
    ['W/"1"', 400],
    ["*", 400],
    ['"-1"', 400],
    ['"9223372036854775808"', 400],
  ] as const)
    expect(
      (await bff(request(path, "PATCH", { "If-Match": value }), path)).status,
    ).toBe(status);
  expect(fetch).not.toHaveBeenCalled();
});
it("serializes CSRF refresh before sharing, without trusting browser credentials", async () => {
  const session = sessions.get(sessionId)!;
  session.accessExpiresAt = 0;
  session.cookies.set(REFRESH_COOKIE, `${REFRESH_COOKIE}=stored-refresh`);
  vi.mocked(fetch).mockResolvedValueOnce(
    Response.json(
      { accessToken: "rotated", tokenType: "Bearer", expiresIn: 1800 },
      {
        headers: {
          "X-VaultDrive-CSRF": "c".repeat(43),
          "Set-Cookie": `${REFRESH_COOKIE}=${"r".repeat(43)}; Max-Age=1000; Path=/; Secure; HttpOnly; SameSite=Strict`,
        },
      },
    ),
  );
  const path = `folders/${id}/grants`;
  const response = await bff(request(path, "POST"), path);
  expect(response.status).toBe(200);
  const auth = new Headers(vi.mocked(fetch).mock.calls[0]![1]!.headers);
  expect(auth.get("X-VaultDrive-CSRF")).toBe("server-csrf");
  expect(auth.get("Cookie")).toContain("stored-refresh");
  expect(
    new Headers(vi.mocked(fetch).mock.calls[1]![1]!.headers).get(
      "Authorization",
    ),
  ).toBe("Bearer rotated");
  expect(response.headers.getSetCookie()).toHaveLength(1);
});
it("rejects query propagation, unsupported methods/routes, oversized and non-JSON mutations", async () => {
  const path = `files/${id}/grants`;
  expect(
    (await bff(request(`${path}?secret=${secret}`, "POST"), path)).status,
  ).toBe(400);
  expect(
    (await bff(request(path, "POST", { "Content-Type": "text/plain" }), path))
      .status,
  ).toBe(415);
  expect(
    (await bff(request(path, "POST", { "Content-Length": "16385" }), path))
      .status,
  ).toBe(413);
  expect((await bff(request(path, "PUT"), path)).status).toBe(405);
  expect(
    (
      await bff(
        request(`folders/${id}/share-links`, "POST"),
        `folders/${id}/share-links`,
      )
    ).status,
  ).toBe(404);
  const revoke = `files/${id}/share-links/${id}`;
  expect(
    (
      await bff(
        new Request(`https://localhost:3000/api/v1/${revoke}`, {
          method: "DELETE",
          headers: {
            Origin: "https://localhost:3000",
            "X-VaultDrive-Client": "1",
          },
          body: "unwanted",
        }),
        revoke,
      )
    ).status,
  ).toBe(400);
  expect(fetch).not.toHaveBeenCalled();
});
it("does not retry uncertain sharing creation or disclose exception input", async () => {
  vi.mocked(fetch).mockRejectedValue(new Error(`provider ${secret}`));
  const path = `files/${id}/share-links`;
  const response = await bff(request(path, "POST"), path);
  expect(response.status).toBe(502);
  expect(await response.text()).not.toContain(secret);
  expect(fetch).toHaveBeenCalledTimes(1);
});
it.each(["", "/download"])(
  "isolates public capability GET%s from expired sessions and credentials",
  async (suffix) => {
    sessions.get(sessionId)!.accessExpiresAt = 0;
    const path = `public/share-links/${id}${suffix}`;
    const response = await bff(
      request(path, "GET", { "X-VaultDrive-Share-Token": secret }),
      path,
    );
    expect(response.status).toBe(200);
    expect(response.headers.get("Referrer-Policy")).toBe("no-referrer");
    expect(response.headers.get("X-Content-Type-Options")).toBe("nosniff");
    expect(response.headers.getSetCookie()).toHaveLength(0);
    const headers = new Headers(vi.mocked(fetch).mock.calls[0]![1]!.headers);
    expect(headers.get("X-VaultDrive-Share-Token")).toBe(secret);
    for (const header of ["Cookie", "Authorization", "X-VaultDrive-CSRF"])
      expect(headers.has(header)).toBe(false);
    expect(fetch).toHaveBeenCalledTimes(1);
  },
);
it("uniformly denies malformed public capabilities, query tokens and cross-site requests", async () => {
  const path = `public/share-links/${id}`;
  for (const [route, method, token, headers] of [
    [path, "GET", "", {}],
    [path, "GET", "A".repeat(42) + "B", {}],
    [path, "GET", `${secret},${secret}`, {}],
    [path + `?secret=${secret}`, "GET", secret, {}],
    [path, "POST", secret, {}],
    ["public/share-links/malformed", "GET", secret, {}],
    [path + "/unknown", "GET", secret, {}],
    [path, "GET", secret, { Origin: "https://foreign.test" }],
    [path, "GET", secret, { "Sec-Fetch-Site": "same-site" }],
  ] as [string, string, string, Record<string, string>][]) {
    const response = await bff(
      request(route, method, { "X-VaultDrive-Share-Token": token, ...headers }),
      route.split("?")[0]!,
    );
    expect(response.status).toBe(404);
    expect(await response.text()).not.toContain(secret);
  }
  expect(fetch).not.toHaveBeenCalled();
});
it.each([400, 401, 403, 404, 429, 500])(
  "sanitizes public backend denial %i without token echo",
  async (status) => {
    vi.mocked(fetch).mockResolvedValue(
      Response.json(
        { message: secret },
        { status, headers: { "Set-Cookie": "untrusted-cookie" } },
      ),
    );
    const path = `public/share-links/${id}`;
    const response = await bff(
      request(path, "GET", { "X-VaultDrive-Share-Token": secret }),
      path,
    );
    expect(response.status).toBe(
      status === 429 ? 429 : status >= 500 ? 502 : 404,
    );
    expect(await response.text()).not.toContain(secret);
    expect(response.headers.getSetCookie()).toHaveLength(0);
  },
);
function native(
  headers: Record<string, string> = {},
  body = JSON.stringify({ secret }),
  query = "",
) {
  return new Request(`https://localhost:3000/share/${id}/download${query}`, {
    method: "POST",
    headers: {
      Origin: "https://localhost:3000",
      "Content-Type": "application/json",
      "X-VaultDrive-Client": "1",
      Cookie: "ambient-cookie",
      Authorization: "ambient-jwt",
      ...headers,
    },
    body,
  });
}
it("streams native public downloads without buffering, session authentication or URL secrets", async () => {
  vi.mocked(fetch).mockResolvedValue(
    new Response("actual-bytes", {
      headers: {
        "Content-Type": "application/octet-stream",
        "Content-Disposition": 'attachment; filename="test.txt"',
        "Content-Length": "12",
      },
    }),
  );
  const prepared = await publicDownload(native(), id);
  expect(prepared.status).toBe(204);
  expect(fetch).not.toHaveBeenCalled();
  const cookie = prepared.headers.getSetCookie()[0]!;
  expect(cookie).toContain("Secure; HttpOnly; SameSite=Strict");
  expect(cookie.includes(secret)).toBe(false);
  const response = await publicDownload(
    new Request(`https://localhost:3000/share/${id}/download`, {
      headers: { Cookie: cookie.split(";")[0]! },
    }),
    id,
  );
  expect(await response.text()).toBe("actual-bytes");
  expect(response.headers.get("Content-Disposition")).toContain("attachment");
  const [url, init] = vi.mocked(fetch).mock.calls[0]!;
  expect(String(url)).toBe(
    `http://127.0.0.1:8080/api/v1/public/share-links/${id}/download`,
  );
  expect(init!.method).toBe("GET");
  expect(init!.body).toBeUndefined();
  const headers = new Headers(init!.headers);
  expect(headers.get("X-VaultDrive-Share-Token")).toBe(secret);
  expect(headers.has("Authorization")).toBe(false);
  expect(headers.has("Cookie")).toBe(false);
});
it("rejects cross-origin preparations, extra/oversized bodies and URL token propagation", async () => {
  for (const req of [
    native({ Origin: "https://foreign.test" }),
    native({ Origin: "" }),
    native({ "Sec-Fetch-Site": "cross-site" }),
    native({}, JSON.stringify({ secret, extra: true })),
    native({}, "secret=" + "a".repeat(1100)),
    native({}, `secret=${secret}`, "?anything=1"),
    native({ "Content-Type": "application/x-www-form-urlencoded" }),
    native({ "X-VaultDrive-Client": "" }),
  ]) {
    const response = await publicDownload(req, id);
    expect(response.status).toBe(404);
    expect(await response.text()).not.toContain(secret);
  }
  expect(fetch).not.toHaveBeenCalled();
});
it("cannot download with a missing, replayed or cross-site ticket", async () => {
  const path = `https://localhost:3000/share/${id}/download`;
  expect((await publicDownload(new Request(path), id)).status).toBe(404);
  const prepared = await publicDownload(native(), id);
  const cookie = prepared.headers.getSetCookie()[0]!.split(";")[0]!;
  expect(
    (
      await publicDownload(
        new Request(path, {
          headers: { Cookie: cookie, "Sec-Fetch-Site": "cross-site" },
        }),
        id,
      )
    ).status,
  ).toBe(404);
  const response = await publicDownload(
    new Request(path, { headers: { Cookie: cookie } }),
    id,
  );
  expect(response.status).toBe(200);
  expect(response.headers.getSetCookie()[0]).toContain("Max-Age=0");
  expect(
    (
      await publicDownload(
        new Request(path, { headers: { Cookie: cookie } }),
        id,
      )
    ).status,
  ).toBe(404);
  expect(fetch).toHaveBeenCalledTimes(1);
});
