import { expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/transport";
import {
  expiryError,
  shareUrl,
  sharingError,
  sharingRequest,
} from "@/features/sharing/api";
import { validSecret } from "@/features/sharing/policy";
const link = {
  id: "11111111-1111-4111-8111-111111111111",
  createdAt: "2026-10-24T00:00:00Z",
  expiresAt: "2026-10-25T00:00:00Z",
  revokedAt: null,
  version: 0,
};
it("constructs only HTTPS fragment capabilities with canonical secrets", () => {
  const secret = "A".repeat(43);
  expect(shareUrl("https://drive.test", { link, secret })).toBe(
    `https://drive.test/share/${link.id}#${secret}`,
  );
  expect(() => shareUrl("http://drive.test", { link, secret })).toThrow();
  for (const value of [
    "A".repeat(42),
    "A".repeat(44),
    "A".repeat(42) + "B",
    secret + "?token=x",
    secret + "," + secret,
  ])
    expect(validSecret(value)).toBe(false);
});
it("validates optional future expiry without replacing server time authority", () => {
  const now = Date.parse(link.createdAt);
  expect(expiryError("", now)).toBeUndefined();
  expect(expiryError(link.expiresAt, now)).toBeUndefined();
  for (const value of ["invalid", link.createdAt, "2026-11-01T00:00:00Z"])
    expect(expiryError(value, now)).toMatch(/future expiry/);
});
it.each([400, 401, 403, 404, 409, 412, 428, 429, 500])(
  "uses controlled sharing feedback for %i without provider text",
  (status) => {
    const message = sharingError(
      new ApiError({ status, error: "ERROR", message: "SECRET provider text" }),
    );
    expect(message).not.toContain("SECRET");
    expect(message.length).toBeGreaterThan(15);
  },
);
it("retains an int64 grant version exactly and sends mutations once", async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValue(
      Response.json({ version: 9007199254740994n.toString() }),
    );
  vi.stubGlobal("fetch", fetcher);
  await sharingRequest(
    "files/id/grants/id",
    "PATCH",
    { role: "EDITOR" },
    9007199254740993n,
  );
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(fetcher.mock.calls[0]![1].headers["If-Match"]).toBe(
    '"9007199254740993"',
  );
  expect(fetcher.mock.calls[0]![1].credentials).toBe("same-origin");
});
