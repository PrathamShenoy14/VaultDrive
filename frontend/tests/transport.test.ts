import { describe, it, expect, vi } from "vitest";
import {
  apiGet,
  authRequest,
  readResponse,
  ApiError,
  errorMessage,
} from "@/lib/api/transport";
import { registerSchema } from "@/features/auth/schema";
import { formatBytes } from "@/features/shell/storage";
describe("typed transport", () => {
  it.each([400, 401, 403, 404, 409, 429, 500, 503])(
    "preserves the %i envelope and Retry-After",
    async (status) => {
      const body = {
        status,
        error: "TEST_ERROR",
        message: "Sanitized backend message",
      };
      await expect(
        readResponse(
          new Response(JSON.stringify(body), {
            status,
            headers: { "Retry-After": "2" },
          }),
        ),
      ).rejects.toMatchObject({ body, retryAfter: "2" });
      expect(errorMessage(new ApiError(body, "2"))).toContain("2");
    },
  );
  it("parses int64 quotas without rounding", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            '{"limitBytes":9223372036854775807,"usedBytes":9007199254740993,"reservedBytes":0,"availableBytes":9214364837600034814,"uncertainStorageBytes":0,"uncertainStorageAllocations":0,"unknownSizeAllocations":0}',
          ),
        ),
    );
    const usage = await apiGet("/api/v1/users/me/storage");
    expect(usage.usedBytes).toBe(9007199254740993n);
    expect(usage.limitBytes).toBe(9223372036854775807n);
    expect(formatBytes(usage.limitBytes)).toBe("7.9 EB");
    expect(fetch).toHaveBeenCalledWith(
      "/api/v1/users/me/storage",
      expect.objectContaining({
        cache: "no-store",
        credentials: "same-origin",
      }),
    );
  });
  it("sends mutations once and supplies BFF custom-header admission", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        new Response(
          '{"status":503,"error":"SERVICE_UNAVAILABLE","message":"Unavailable"}',
          { status: 503 },
        ),
      );
    vi.stubGlobal("fetch", fetcher);
    await expect(
      authRequest("login", { email: "a@example.test", password: "secret" }),
    ).rejects.toBeInstanceOf(ApiError);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/v1/auth/login",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({ "X-VaultDrive-Client": "1" }),
      }),
    );
  });
  it("sanitizes non-JSON upstream failures", async () => {
    await expect(
      readResponse(
        new Response("<html>private proxy exception</html>", { status: 502 }),
      ),
    ).rejects.toMatchObject({
      status: 502,
      message: "The service could not complete this request.",
    });
  });
  it("matches the registration character and UTF-8 password limits", () => {
    const input = {
      email: "a@example.test",
      displayName: "Alice",
      password: "😀".repeat(19),
    };
    expect(registerSchema.safeParse(input).success).toBe(false);
    expect(
      registerSchema.safeParse({ ...input, password: "x".repeat(12) }).success,
    ).toBe(true);
    expect(
      registerSchema.safeParse({
        ...input,
        displayName: " ",
        password: "x".repeat(12),
      }).success,
    ).toBe(false);
  });
});
