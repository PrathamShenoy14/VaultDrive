// @vitest-environment node
import { expect, it } from "vitest";
import {
  issueDownload,
  takeDownload,
} from "@/features/sharing/server/download-tickets";
const request = (cookie = "") =>
  new Request("https://localhost:3000/share/link/download", {
    headers: { Cookie: cookie },
  });
const cookieOnly = (cookie: string) => cookie.split(";")[0]!;
it("binds one-use opaque tickets to the selected link and expires them after a minute", () => {
  const now = Date.now();
  const cookie = issueDownload(request(), "link-a", "secret-a", now)!;
  expect(cookie).toContain("Secure; HttpOnly; SameSite=Strict");
  expect(cookie).not.toContain("secret-a");
  const reader = request(cookieOnly(cookie));
  expect(takeDownload(reader, "link-b", now + 1)).toBeUndefined();
  expect(takeDownload(reader, "link-a", now + 1)).toBe("secret-a");
  expect(takeDownload(reader, "link-a", now + 2)).toBeUndefined();
  const expiring = issueDownload(request(), "link-a", "secret-b", now)!;
  expect(
    takeDownload(request(cookieOnly(expiring)), "link-a", now + 60000),
  ).toBeUndefined();
});
it("rejects duplicate cookies and replaces the same browser's pending handoff", () => {
  const cookie = cookieOnly(issueDownload(request(), "link", "old")!);
  expect(takeDownload(request(`${cookie}; ${cookie}`), "link")).toBeUndefined();
  const next = cookieOnly(issueDownload(request(cookie), "link", "new")!);
  expect(takeDownload(request(cookie), "link")).toBeUndefined();
  expect(takeDownload(request(next), "link")).toBe("new");
});
it("bounds retained pending handoffs and frees expired capacity", () => {
  const now = Date.now();
  for (let index = 0; index < 256; index++)
    expect(
      issueDownload(request(), `link-${index}`, "synthetic-secret", now),
    ).toBeDefined();
  expect(
    issueDownload(request(), "overflow", "synthetic-secret", now),
  ).toBeUndefined();
  const next = issueDownload(
    request(),
    "after-expiry",
    "synthetic-secret",
    now + 60000,
  )!;
  expect(next).toBeDefined();
  expect(
    takeDownload(request(cookieOnly(next)), "after-expiry", now + 60001),
  ).toBe("synthetic-secret");
});
