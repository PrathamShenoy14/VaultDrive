// @vitest-environment node
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { bff } from "@/features/auth/server/bff";
import {
  sessions,
  sessionCookie,
  REFRESH_COOKIE,
} from "@/features/auth/server/session-store";
const id = "11111111-1111-4111-8111-111111111111";
let sessionId: string;
const routes: [string, string, boolean?, boolean?][] = [
  ["folders", "POST", true],
  [`folders/${id}`, "PATCH", true],
  [`folders/${id}`, "DELETE"],
  [`files/${id}`, "DELETE"],
  [`files/${id}/name`, "PATCH", true],
  [`files/${id}/move`, "PATCH", true],
  [`folders/${id}/move`, "PATCH", true],
  [`files/${id}/restore`, "POST"],
  [`trash/folders/${id}/restore`, "POST"],
  [`files/${id}/permanent`, "DELETE"],
  [`trash/folders/${id}/permanent`, "DELETE"],
  [`files/${id}/copy`, "POST", true, true],
  [`folders/${id}/copy`, "POST", true, true],
  ...["copy-operations", "folder-copy-operations"].flatMap(
    (kind): [string, string][] => [
      [`${kind}/${id}`, "GET"],
      ...["execute", "recover", "cancel"].map((action): [string, string] => [
        `${kind}/${id}/${action}`,
        "POST",
      ]),
    ],
  ),
  [`folder-copy-operations/${id}/discard`, "POST"],
  [`deletion-operations/${id}`, "GET"],
];
function request(
  path: string,
  method: string,
  json?: boolean,
  headers?: Record<string, string>,
) {
  return new Request(`https://localhost:3000/api/v1/${path}`, {
    method,
    headers: {
      Origin: "https://localhost:3000",
      "X-VaultDrive-Client": "1",
      Cookie: sessionCookie(sessionId, 1000).split(";")[0]!,
      ...(json ? { "Content-Type": "application/json" } : {}),
      "Idempotency-Key": "retained-key",
      Authorization: "browser-injected",
      "X-VaultDrive-CSRF": "browser-injected",
      ...headers,
    },
    ...(json ? { body: '{"name":"New name"}' } : {}),
  });
}
beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn());
  sessionId = sessions.create(
    {
      access: "server-access",
      csrf: "server-csrf",
      cookies: new Map(),
      accessExpiresAt: Date.now() + 1800000,
      expiresAt: Date.now() + 604800000,
    },
    10000,
  )!.id;
});
afterEach(() => sessions.delete(sessionId));
it("serializes stored-CSRF refresh before a new mutation and forwards rotated cookies", async () => {
  const session = sessions.get(sessionId)!;
  session.accessExpiresAt = Date.now() - 1000;
  session.cookies.set(REFRESH_COOKIE, `${REFRESH_COOKIE}=server-refresh`);
  vi.mocked(fetch)
    .mockResolvedValueOnce(
      Response.json(
        {
          accessToken: "rotated-server-access",
          tokenType: "Bearer",
          expiresIn: 1800,
        },
        {
          headers: {
            "X-VaultDrive-CSRF": "c".repeat(43),
            "Set-Cookie": `${REFRESH_COOKIE}=${"r".repeat(43)}; Max-Age=1000; Path=/; Secure; HttpOnly; SameSite=Strict`,
          },
        },
      ),
    )
    .mockResolvedValueOnce(Response.json({ folderId: id }, { status: 201 }));
  const response = await bff(request("folders", "POST", true), "folders");
  expect(response.status).toBe(201);
  const refresh = new Headers(vi.mocked(fetch).mock.calls[0]![1]!.headers),
    mutation = new Headers(vi.mocked(fetch).mock.calls[1]![1]!.headers);
  expect(refresh.get("X-VaultDrive-CSRF")).toBe("server-csrf");
  expect(refresh.get("Cookie")).toContain("server-refresh");
  expect(refresh.has("Authorization")).toBe(false);
  expect(mutation.get("Authorization")).toBe("Bearer rotated-server-access");
  expect(mutation.has("X-VaultDrive-CSRF")).toBe(false);
  expect(response.headers.getSetCookie()).toHaveLength(1);
});
it("fails closed after an uncertain refresh without dispatching the mutation", async () => {
  sessions.get(sessionId)!.accessExpiresAt = Date.now() - 1000;
  vi.mocked(fetch).mockRejectedValue(new TypeError("lost rotation"));
  expect((await bff(request("folders", "POST", true), "folders")).status).toBe(
    502,
  );
  expect(sessions.get(sessionId)).toBeUndefined();
  expect(fetch).toHaveBeenCalledTimes(1);
});
it.each(routes)(
  "forwards M4 %s %s once under server authentication",
  async (path, method, json, key) => {
    vi.mocked(fetch).mockResolvedValue(
      Response.json({ state: "UNKNOWN" }, { status: 202 }),
    );
    const response = await bff(request(path, method, json), path);
    expect(response.status).toBe(202);
    expect(response.headers.get("Cache-Control")).toContain("no-store");
    expect(fetch).toHaveBeenCalledTimes(1);
    const init = vi.mocked(fetch).mock.calls[0]![1]!;
    const headers = new Headers(init.headers);
    expect(init.method).toBe(method);
    expect(headers.get("Authorization")).toBe("Bearer server-access");
    expect(headers.has("Cookie")).toBe(false);
    expect(headers.has("X-VaultDrive-CSRF")).toBe(false);
    expect(headers.get("Idempotency-Key")).toBe(key ? "retained-key" : null);
    expect(init.body).toBe(json ? '{"name":"New name"}' : undefined);
  },
);
it.each(routes.filter(([, method]) => method !== "GET"))(
  "rejects foreign Origin for %s %s",
  async (path, method, json) => {
    expect(
      (
        await bff(
          request(path, method, json, { Origin: "https://foreign.test" }),
          path,
        )
      ).status,
    ).toBe(403);
    expect(fetch).not.toHaveBeenCalled();
  },
);
it.each(["POST", "PATCH", "DELETE"])(
  "requires custom admission header, same-site metadata and session on %s",
  async (method) => {
    const path = method === "POST" ? "folders" : `folders/${id}`;
    for (const headers of [
      { "X-VaultDrive-Client": "" },
      { "Sec-Fetch-Site": "same-site" },
      { "Sec-Fetch-Site": "cross-site" },
    ] as Record<string, string>[])
      expect(
        (await bff(request(path, method, method !== "DELETE", headers), path))
          .status,
      ).toBe(403);
    sessions.delete(sessionId);
    expect(
      (await bff(request(path, method, method !== "DELETE"), path)).status,
    ).toBe(401);
    expect(fetch).not.toHaveBeenCalled();
  },
);
it("rejects query forwarding, invalid copy keys, bodies on no-body controls and non-JSON names", async () => {
  for (const [path, method, json, headers, status] of [
    ["folders?page=1", "POST", true, {}, 400],
    [`files/${id}/copy`, "POST", true, { "Idempotency-Key": "bad key" }, 400],
    [`copy-operations/${id}/recover`, "POST", true, {}, 400],
    ["folders", "POST", true, { "Content-Type": "text/plain" }, 415],
  ] as const)
    expect(
      (await bff(request(path, method, json, headers), path.split("?")[0]!))
        .status,
    ).toBe(status);
  expect(fetch).not.toHaveBeenCalled();
});
it("retains async identity and does not retry lost delete or move responses", async () => {
  vi.mocked(fetch).mockResolvedValueOnce(
    Response.json(
      { operationId: id },
      {
        status: 202,
        headers: {
          Location: `/api/v1/deletion-operations/${id}`,
          "Retry-After": "2",
        },
      },
    ),
  );
  const path = `files/${id}/permanent`;
  const response = await bff(request(path, "DELETE"), path);
  expect(response.status).toBe(202);
  expect(response.headers.get("Retry-After")).toBe("2");
  expect(await response.json()).toEqual({ operationId: id });
  vi.mocked(fetch).mockRejectedValue(new TypeError("lost"));
  expect((await bff(request(path, "DELETE"), path)).status).toBe(502);
  expect(
    (await bff(request(`files/${id}/move`, "PATCH", true), `files/${id}/move`))
      .status,
  ).toBe(502);
  expect(fetch).toHaveBeenCalledTimes(3);
});
