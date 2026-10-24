// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { bff } from "@/features/auth/server/bff";
import { sessions, sessionCookie } from "@/features/auth/server/session-store";

const uuid = "11111111-1111-4111-8111-111111111111";
const ids: string[] = [];
function fixture() {
  const session = sessions.create(
    {
      access: "server-held-access",
      csrf: "server-held-csrf",
      cookies: new Map(),
      accessExpiresAt: Date.now() + 1800000,
      expiresAt: Date.now() + 604800000,
    },
    10000,
  )!;
  ids.push(session.id);
  return session;
}
function request(path: string, id?: string, init: RequestInit = {}) {
  return new Request(`https://localhost:3000/api/v1/${path}`, {
    ...init,
    headers: {
      Origin: "https://localhost:3000",
      "X-VaultDrive-Client": "1",
      ...(id ? { Cookie: sessionCookie(id, 600000).split(";")[0]! } : {}),
      ...Object.fromEntries(new Headers(init.headers)),
    },
  });
}
beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn());
});
afterEach(() => ids.splice(0).forEach((id) => sessions.delete(id)));
describe("M3 BFF control-plane and streaming boundary (mocked backend)", () => {
  it.each([
    "upload-sessions",
    "files/empty",
    `upload-sessions/${uuid}/part-urls`,
  ])(
    "forwards bounded JSON and original key with server bearer only: %s",
    async (path) => {
      const session = fixture();
      vi.mocked(fetch).mockResolvedValue(
        Response.json(
          { id: uuid },
          {
            status: 201,
            headers: { Location: `/api/v1/upload-sessions/${uuid}` },
          },
        ),
      );
      const response = await bff(
        request(path, session.id, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Idempotency-Key": "original-key",
            Authorization: "attacker",
            "X-VaultDrive-CSRF": "attacker",
          },
          body: '{"name":"file.bin"}',
        }),
        path,
      );
      expect(response.status).toBe(201);
      expect(response.headers.get("Location")).toBe(
        `/api/v1/upload-sessions/${uuid}`,
      );
      const sent = vi.mocked(fetch).mock.calls[0]![1]!,
        headers = new Headers(sent.headers);
      expect(sent.body).toBe('{"name":"file.bin"}');
      expect(headers.get("Authorization")).toBe("Bearer server-held-access");
      expect(headers.has("Cookie")).toBe(false);
      expect(headers.has("X-VaultDrive-CSRF")).toBe(false);
      expect(headers.get("Idempotency-Key")).toBe(
        path.endsWith("part-urls") ? null : "original-key",
      );
      expect(fetch).toHaveBeenCalledTimes(1);
    },
  );
  it.each(["initiate", "complete", "recover", "cancel", "cancel/recover"])(
    "exposes only documented no-body %s once",
    async (suffix) => {
      const session = fixture(),
        path = `upload-sessions/${uuid}/${suffix}`;
      vi.mocked(fetch).mockResolvedValue(
        Response.json({ state: "UNKNOWN" }, { status: 202 }),
      );
      expect(
        (await bff(request(path, session.id, { method: "POST" }), path)).status,
      ).toBe(202);
      expect(fetch).toHaveBeenCalledTimes(1);
      expect(
        (
          await bff(
            request(path, session.id, { method: "POST", body: "bytes" }),
            path,
          )
        ).status,
      ).toBe(400);
      expect(fetch).toHaveBeenCalledTimes(1);
    },
  );
  it.each([
    `upload-sessions/${uuid}`,
    `upload-sessions/${uuid}/cancellation`,
    `upload-sessions/${uuid}/parts?marker=1&limit=1`,
    `empty-file-creations/${uuid}`,
  ])("retains status and part reads: %s", async (path) => {
    const session = fixture();
    vi.mocked(fetch).mockResolvedValue(Response.json({ state: "OPEN" }));
    expect(
      (await bff(request(path, session.id), path.split("?")[0]!)).status,
    ).toBe(200);
  });
  it.each(["http://localhost:3000", "https://foreign.test"])(
    "rejects mutation Origin %s before backend work",
    async (origin) => {
      const session = fixture();
      expect(
        (
          await bff(
            request("upload-sessions", session.id, {
              method: "POST",
              headers: {
                Origin: origin,
                "Content-Type": "application/json",
                "Idempotency-Key": uuid,
              },
              body: "{}",
            }),
            "upload-sessions",
          )
        ).status,
      ).toBe(403);
      expect(fetch).not.toHaveBeenCalled();
    },
  );
  it.each(["multipart/form-data; boundary=x", "application/octet-stream"])(
    "rejects byte body media %s",
    async (type) => {
      expect(
        (
          await bff(
            request("upload-sessions", undefined, {
              method: "POST",
              headers: { "Content-Type": type },
              body: "bytes",
            }),
            "upload-sessions",
          )
        ).status,
      ).toBe(415);
      expect(fetch).not.toHaveBeenCalled();
    },
  );
  it("bounds JSON bodies and rejects unknown/duplicate marker queries", async () => {
    expect(
      (
        await bff(
          request("upload-sessions", undefined, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: "x".repeat(16385),
          }),
          "upload-sessions",
        )
      ).status,
    ).toBe(413);
    for (const query of ["marker=1&marker=2", "uploadId=secret"])
      expect(
        (
          await bff(
            request(`upload-sessions/${uuid}/parts?${query}`),
            `upload-sessions/${uuid}/parts`,
          )
        ).status,
      ).toBe(400);
    expect(fetch).not.toHaveBeenCalled();
  });
  it("does not retry a lost completion response", async () => {
    const session = fixture(),
      path = `upload-sessions/${uuid}/complete`;
    vi.mocked(fetch).mockRejectedValue(new TypeError("lost"));
    expect(
      (await bff(request(path, session.id, { method: "POST" }), path)).status,
    ).toBe(502);
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it("streams a declared 5-GiB response before its producer finishes, preserving MIME/disposition/length and releasing the session lock", async () => {
    const session = fixture(),
      path = `files/${uuid}/download`;
    let producer!: ReadableStreamDefaultController<Uint8Array>;
    const source = new ReadableStream<Uint8Array>(
      {
        start(controller) {
          producer = controller;
          controller.enqueue(new Uint8Array([1, 2, 3]));
        },
      },
      { highWaterMark: 0 },
    );
    vi.mocked(fetch)
      .mockResolvedValueOnce(
        new Response(source, {
          headers: {
            "Content-Type": "application/example",
            "Content-Disposition": 'attachment; filename="large.bin"',
            "Content-Length": "5368709120",
            "X-Content-Type-Options": "nosniff",
          },
        }),
      )
      .mockResolvedValueOnce(Response.json({ profile: true }));
    const response = await bff(request(path, session.id), path);
    expect(response.headers.get("Content-Length")).toBe("5368709120");
    expect(response.headers.get("Content-Type")).toBe("application/example");
    expect(response.headers.get("Content-Disposition")).toContain(
      'filename="large.bin"',
    );
    expect(response.headers.get("Cache-Control")).toContain("no-store");
    const reader = response.body!.getReader();
    expect((await reader.read()).value).toEqual(new Uint8Array([1, 2, 3]));
    expect(
      (await bff(request("users/me", session.id), "users/me")).status,
    ).toBe(200);
    producer.enqueue(new Uint8Array([4]));
    expect((await reader.read()).value).toEqual(new Uint8Array([4]));
    producer.close();
    const headers = new Headers(vi.mocked(fetch).mock.calls[0]![1]!.headers);
    expect(headers.get("Accept-Encoding")).toBe("identity");
    expect(headers.get("Authorization")).toBe("Bearer server-held-access");
  });
  it("propagates body cancellation to the upstream stream", async () => {
    const session = fixture(),
      cancel = vi.fn();
    vi.mocked(fetch).mockResolvedValue(
      new Response(new ReadableStream({ cancel }, { highWaterMark: 0 })),
    );
    const response = await bff(
      request(`files/${uuid}/download`, session.id),
      `files/${uuid}/download`,
    );
    await response.body!.cancel();
    expect(cancel).toHaveBeenCalledTimes(1);
  });
  it("preserves zero-length attachment downloads", async () => {
    const session = fixture();
    vi.mocked(fetch).mockResolvedValue(
      new Response(null, {
        headers: {
          "Content-Length": "0",
          "Content-Type": "text/plain",
          "Content-Disposition": 'attachment; filename="empty.txt"',
        },
      }),
    );
    const response = await bff(
      request(`files/${uuid}/download`, session.id),
      `files/${uuid}/download`,
    );
    expect(response.headers.get("Content-Length")).toBe("0");
    expect(await response.text()).toBe("");
  });
  it.each(["cross-site", "same-site"])(
    "rejects %s download navigation",
    async (site) => {
      const path = `files/${uuid}/download`;
      expect(
        (
          await bff(
            request(path, fixture().id, {
              headers: { "Sec-Fetch-Site": site },
            }),
            path,
          )
        ).status,
      ).toBe(403);
      expect(fetch).not.toHaveBeenCalled();
    },
  );
  it.each([401, 403, 404, 429, 502])(
    "renders a safe actionable native download error for HTTP %i",
    async (status) => {
      const path = `files/${uuid}/download`;
      vi.mocked(fetch).mockResolvedValue(
        Response.json(
          {
            status,
            error: "ERROR",
            message: "never render provider private URL",
          },
          { status },
        ),
      );
      const response = await bff(
        request(path, fixture().id, { headers: { Accept: "text/html" } }),
        path,
      );
      expect(response.status).toBe(status);
      const html = await response.text();
      expect(html).toContain("Download unavailable");
      expect(html).toContain("/login");
      expect(html).not.toContain("provider private URL");
      expect(response.headers.get("Content-Type")).toContain("text/html");
    },
  );
});
