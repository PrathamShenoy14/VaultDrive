// @vitest-environment node
import { beforeEach, expect, it, vi } from "vitest";
import { bff } from "@/features/auth/server/bff";
import {
  sessions,
  sessionCookie,
  REFRESH_COOKIE,
} from "@/features/auth/server/session-store";
const id = "11111111-1111-4111-8111-111111111111";
function fixture(expired = false) {
  return sessions.create(
    {
      access: "server-held",
      csrf: "server-csrf",
      cookies: new Map([[REFRESH_COOKIE, `${REFRESH_COOKIE}=server-refresh`]]),
      accessExpiresAt: Date.now() + (expired ? -1 : 600000),
      expiresAt: Date.now() + 600000,
    },
    10000,
  )!;
}
function request(
  path: string,
  session?: string,
  method = "GET",
  headers: Record<string, string> = {},
  body?: string,
) {
  return new Request(`https://localhost:3000/api/v1/${path}`, {
    method,
    headers: {
      Origin: "https://localhost:3000",
      "X-VaultDrive-Client": "1",
      ...(session
        ? { Cookie: sessionCookie(session, 600).split(";")[0]! }
        : {}),
      ...headers,
    },
    ...(body ? { body } : {}),
  });
}
beforeEach(() => vi.stubGlobal("fetch", vi.fn()));
it.each([
  "notifications?unread=true&eventType=GRANT_CREATED&page=1&size=20",
  "notifications/unread-count",
  "activity?resourceType=FILE&resourceId=" +
    id +
    "&actorId=" +
    id +
    "&from=2026-01-01T00:00:00Z&to=2026-02-01T00:00:00Z&page=1&size=20",
  "permission-activity?eventType=GRANT_REVOKED&page=1&size=20",
  "users/me",
  "users/me/storage",
])("forwards only documented scoped GET %s", async (path) => {
  const session = fixture();
  vi.mocked(fetch).mockResolvedValue(Response.json({ content: [] }));
  const response = await bff(request(path, session.id), path.split("?")[0]!);
  expect(response.status).toBe(200);
  expect(response.headers.get("Cache-Control")).toContain("no-store");
  const call = vi.mocked(fetch).mock.calls[0]!;
  expect(String(call[0])).toBe(`http://127.0.0.1:8080/api/v1/${path}`);
  expect(new Headers(call[1]?.headers).get("Authorization")).toBe(
    "Bearer server-held",
  );
  expect(new Headers(call[1]?.headers).has("Cookie")).toBe(false);
  sessions.delete(session.id);
});
it("does not refresh or dispatch cancelled M6 work", async () => {
  const session = fixture(true),
    controller = new AbortController();
  controller.abort();
  const cancelled = new Request(request("activity", session.id), {
    signal: controller.signal,
  });
  expect((await bff(cancelled, "activity")).status).toBe(400);
  expect(fetch).not.toHaveBeenCalled();
  sessions.delete(session.id);
});
it.each([`notifications/${id}/read`, "users/me"])(
  "protects M6 PATCH %s with exact origin and client/CSRF admission",
  async (path) => {
    const session = fixture();
    const rejectedHeaders: Record<string, string>[] = [
      { Origin: "http://localhost:3000" },
      { Origin: "https://foreign.test" },
      { Origin: "null" },
      { "X-VaultDrive-Client": "" },
      { "Sec-Fetch-Site": "same-site" },
      { "Sec-Fetch-Site": "cross-site" },
    ];
    for (const headers of rejectedHeaders) {
      expect(
        (await bff(request(path, session.id, "PATCH", headers), path)).status,
      ).toBe(403);
    }
    expect(fetch).not.toHaveBeenCalled();
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }));
    const body =
      path === "users/me"
        ? JSON.stringify({ displayName: "Updated" })
        : undefined;
    expect(
      (
        await bff(
          request(
            path,
            session.id,
            "PATCH",
            {
              "Content-Type": "application/json",
              Authorization: "Bearer attacker",
              "X-VaultDrive-CSRF": "attacker",
            },
            body,
          ),
          path,
        )
      ).status,
    ).toBe(204);
    const sent = new Headers(vi.mocked(fetch).mock.calls[0]![1]?.headers);
    expect(sent.get("Authorization")).toBe("Bearer server-held");
    expect(sent.has("X-VaultDrive-CSRF")).toBe(false);
    expect(fetch).toHaveBeenCalledTimes(1);
    sessions.delete(session.id);
  },
);
it.each([
  "notifications?recipientId=" + id,
  "notifications?unread=true&unread=false",
  "activity?ownerId=" + id,
  "permission-activity?actorId=" + id,
  `notifications/${id}/read?unread=true`,
])("rejects unsupported or duplicate query %s", async (path) => {
  expect(
    (
      await bff(
        request(path, undefined, path.includes("/read?") ? "PATCH" : "GET"),
        path.split("?")[0]!,
      )
    ).status,
  ).toBe(400);
  expect(fetch).not.toHaveBeenCalled();
});
it.each([
  "notifications/read-all",
  `notifications/${id}/unread`,
  "users/me/password",
  "users/me/email",
  "notifications/bad-id/read",
])("does not invent %s", async (path) => {
  expect((await bff(request(path, undefined, "PATCH"), path)).status).toBe(404);
  expect(fetch).not.toHaveBeenCalled();
});
it("requires JSON profile data and no mark-read body, and never retries", async () => {
  expect(
    (await bff(request("users/me", undefined, "PATCH"), "users/me")).status,
  ).toBe(415);
  expect(
    (
      await bff(
        request(`notifications/${id}/read`, undefined, "PATCH", {}, "{}"),
        `notifications/${id}/read`,
      )
    ).status,
  ).toBe(400);
  const session = fixture();
  vi.mocked(fetch).mockRejectedValue(new Error("lost response"));
  expect(
    (
      await bff(
        request(`notifications/${id}/read`, session.id, "PATCH"),
        `notifications/${id}/read`,
      )
    ).status,
  ).toBe(502);
  expect(fetch).toHaveBeenCalledTimes(1);
  sessions.delete(session.id);
});
it.each([401, 403, 404])(
  "preserves stale authorization %i without retry or refresh",
  async (status) => {
    const session = fixture();
    vi.mocked(fetch).mockResolvedValue(
      Response.json(
        { status, error: "DENIED", message: "Unavailable" },
        { status },
      ),
    );
    expect(
      (await bff(request("activity", session.id), "activity")).status,
    ).toBe(status);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(!!sessions.get(session.id)).toBe(status !== 401);
    sessions.delete(session.id);
  },
);
it("ends the local session on refresh failure before fetching history", async () => {
  const session = fixture(true);
  vi.mocked(fetch).mockResolvedValue(
    Response.json(
      { status: 401, error: "UNAUTHORIZED", message: "Expired" },
      { status: 401 },
    ),
  );
  expect(
    (await bff(request("notifications", session.id), "notifications")).status,
  ).toBe(401);
  expect(sessions.get(session.id)).toBeUndefined();
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(String(vi.mocked(fetch).mock.calls[0]![0])).toContain("auth/refresh");
});
it.each([
  "notifications",
  "notifications/unread-count",
  "activity",
  "permission-activity",
])("rejects unauthenticated %s", async (path) => {
  expect((await bff(request(path), path)).status).toBe(401);
  expect(fetch).not.toHaveBeenCalled();
});
