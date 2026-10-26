// @vitest-environment node
import { beforeEach, expect, it, vi } from "vitest";
import { bff } from "@/features/auth/server/bff";
import {
  sessions,
  SESSION_COOKIE,
  REFRESH_COOKIE,
} from "@/features/auth/server/session-store";
const id = "11111111-1111-4111-8111-111111111111";
function fixture(expired = false) {
  return sessions.create(
    {
      access: "server-only",
      csrf: "A".repeat(43),
      cookies: new Map([
        [REFRESH_COOKIE, `${REFRESH_COOKIE}=${"B".repeat(43)}`],
      ]),
      accessExpiresAt: Date.now() + (expired ? -1 : 600000),
      expiresAt: Date.now() + 600000,
    },
    10000,
  )!;
}
function request(
  path: string,
  sessionId: string,
  signal: AbortSignal,
  method: string,
  body?: object,
) {
  return new Request(`https://localhost:3000/api/v1/${path}`, {
    method,
    signal,
    headers: {
      Cookie: `${SESSION_COOKIE}=${sessionId}`,
      Origin: "https://localhost:3000",
      "X-VaultDrive-Client": "1",
      "Content-Type": "application/json",
      "Idempotency-Key": "original-key",
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
}
beforeEach(() => vi.stubGlobal("fetch", vi.fn()));
it.each([
  ["folders", "GET", undefined],
  ["folders", "POST", { name: "Folder" }],
  ["upload-sessions", "POST", { name: "data.bin", size: 3 }],
  [`files/${id}/name`, "PATCH", { name: "Name.txt" }],
  [`files/${id}/grants`, "POST", { email: "a@example.test", role: "VIEWER" }],
  [`copy-operations/${id}/recover`, "POST", undefined],
  [`files/${id}/download`, "GET", undefined],
] as const)(
  "does not refresh or dispatch cancelled %s %s after waiting for the credential lock",
  async (path, method, body) => {
    const session = fixture(true);
    let release!: () => void;
    const holding = sessions.locked(
      session.id,
      () =>
        new Promise<void>((resolve) => {
          release = resolve;
        }),
    );
    await vi.waitFor(() => expect(release).toBeDefined());
    const controller = new AbortController();
    const pending = bff(
      request(path, session.id, controller.signal, method, body),
      path,
    );
    controller.abort();
    release();
    await holding;
    expect((await pending).status).toBe(400);
    expect(fetch).not.toHaveBeenCalled();
    expect(sessions.get(session.id)).toBeDefined();
    sessions.delete(session.id);
  },
);
it("retains a completed serialized refresh but fences a mutation cancelled during rotation", async () => {
  const session = fixture(true);
  let finish!: (response: Response) => void;
  vi.mocked(fetch).mockImplementation(
    () =>
      new Promise<Response>((resolve) => {
        finish = resolve;
      }),
  );
  const controller = new AbortController();
  const pending = bff(
    request("folders", session.id, controller.signal, "POST", {
      name: "Folder",
    }),
    "folders",
  );
  await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
  controller.abort();
  finish(
    Response.json(
      {
        accessToken: "rotated-server-only",
        tokenType: "Bearer",
        expiresIn: 1800,
      },
      {
        headers: {
          "X-VaultDrive-CSRF": "A".repeat(43),
          "Set-Cookie": `${REFRESH_COOKIE}=${"C".repeat(43)}; Max-Age=600; Path=/; Secure; HttpOnly; SameSite=Strict`,
        },
      },
    ),
  );
  const response = await pending;
  expect(response.status).toBe(400);
  expect(response.headers.getSetCookie()).toHaveLength(1);
  expect(sessions.get(session.id)?.access).toBe("rotated-server-only");
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(String(vi.mocked(fetch).mock.calls[0]![0])).toContain("auth/refresh");
  sessions.delete(session.id);
});
