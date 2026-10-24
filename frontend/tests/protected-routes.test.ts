// @vitest-environment node
import { describe, it, expect, vi } from "vitest";
import { sessions, SESSION_COOKIE } from "@/features/auth/server/session-store";
const { cookieGet, redirect } = vi.hoisted(() => ({
  cookieGet: vi.fn(),
  redirect: vi.fn((url: string) => {
    throw new Error(`REDIRECT:${url}`);
  }),
}));
vi.mock("next/headers", () => ({ cookies: async () => ({ get: cookieGet }) }));
vi.mock("next/navigation", () => ({ redirect }));
import { protectedRead } from "@/features/auth/server/current-user";
describe("server protected reads", () => {
  it("redirects absent sessions before dispatch", async () => {
    cookieGet.mockReturnValue(undefined);
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    await expect(protectedRead("/api/v1/users/me")).rejects.toThrow(
      "REDIRECT:/login?reason=session",
    );
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("uses the restore route for expired access without rotating during RSC rendering", async () => {
    const session = sessions.create(
      {
        access: "access",
        csrf: "csrf",
        cookies: new Map(),
        accessExpiresAt: 0,
        expiresAt: Date.now() + 100000,
      },
      10000,
    )!;
    cookieGet.mockImplementation((name: string) =>
      name === SESSION_COOKIE ? { value: session.id } : undefined,
    );
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    await expect(protectedRead("/api/v1/users/me")).rejects.toThrow(
      "REDIRECT:/session/restore",
    );
    expect(fetcher).not.toHaveBeenCalled();
    sessions.delete(session.id);
  });
  it("rejects a revoked bearer on the server", async () => {
    const session = sessions.create(
      {
        access: "revoked",
        csrf: "csrf",
        cookies: new Map(),
        accessExpiresAt: Date.now() + 100000,
        expiresAt: Date.now() + 100000,
      },
      10000,
    )!;
    cookieGet.mockReturnValue({ value: session.id });
    const fetcher = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 401 }));
    vi.stubGlobal("fetch", fetcher);
    await expect(protectedRead("/api/v1/users/me")).rejects.toThrow(
      "REDIRECT:/login?reason=session",
    );
    expect(sessions.get(session.id)).toBeUndefined();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
});
