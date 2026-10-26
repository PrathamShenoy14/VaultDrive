import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { boundedPoll } from "@/features/experience/poll";
import { experienceRequest } from "@/features/experience/api";
import {
  pauseSession,
  resumeSession,
  sessionSignal,
} from "@/features/auth/client-session";
import { timestamp } from "@/features/experience/contracts";
import { quotaWarning } from "@/features/shell/storage";
beforeEach(() => resumeSession());
afterEach(() => vi.useRealTimers());
it("bounds polling at five attempts and cleans up", async () => {
  vi.useFakeTimers();
  const run = vi.fn().mockResolvedValue(undefined),
    paused = vi.fn();
  const stop = boundedPoll(run, paused);
  await vi.advanceTimersByTimeAsync(600000);
  expect(run).toHaveBeenCalledTimes(5);
  expect(paused).toHaveBeenCalledTimes(1);
  stop();
  await vi.advanceTimersByTimeAsync(600000);
  expect(run).toHaveBeenCalledTimes(5);
});
it("never overlaps requests, cancels hidden work and waits before resuming", async () => {
  vi.useFakeTimers();
  let hidden = false;
  vi.spyOn(document, "visibilityState", "get").mockImplementation(() =>
    hidden ? "hidden" : "visible",
  );
  const signals: AbortSignal[] = [];
  const run = vi.fn((signal: AbortSignal) => {
    signals.push(signal);
    return new Promise<void>((_, reject) =>
      signal.addEventListener("abort", () => reject(new Error("aborted"))),
    );
  });
  const stop = boundedPoll(run, vi.fn());
  await vi.advanceTimersByTimeAsync(180000);
  expect(run).toHaveBeenCalledTimes(1);
  hidden = true;
  document.dispatchEvent(new Event("visibilitychange"));
  await vi.advanceTimersByTimeAsync(1);
  expect(signals[0]?.aborted).toBe(true);
  await vi.advanceTimersByTimeAsync(180000);
  expect(run).toHaveBeenCalledTimes(1);
  hidden = false;
  document.dispatchEvent(new Event("visibilitychange"));
  await vi.advanceTimersByTimeAsync(59999);
  expect(run).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(1);
  expect(run).toHaveBeenCalledTimes(2);
  stop();
  expect(signals[1]?.aborted).toBe(true);
});
it("stops polling on errors without retry storms", async () => {
  vi.useFakeTimers();
  const run = vi.fn().mockRejectedValue(new Error("429"));
  const stop = boundedPoll(run, vi.fn());
  await vi.advanceTimersByTimeAsync(600000);
  expect(run).toHaveBeenCalledTimes(1);
  stop();
});
it("does not notify after cleanup while the last attempt is settling", async () => {
  let finish!: () => void;
  const settled = vi.fn();
  const stop = boundedPoll(
    () =>
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
    settled,
    60000,
    1,
  );
  stop();
  finish();
  await Promise.resolve();
  await Promise.resolve();
  expect(settled).not.toHaveBeenCalled();
});
it("cancels in-flight account/history requests on logout and ignores late success", async () => {
  let resolve!: (response: Response) => void;
  const fetcher = vi.fn().mockImplementation(
    () =>
      new Promise<Response>((done) => {
        resolve = done;
      }),
  );
  vi.stubGlobal("fetch", fetcher);
  const result = experienceRequest("activity", new AbortController().signal);
  const rejection = expect(result).rejects.toBeDefined();
  pauseSession();
  expect((fetcher.mock.calls[0]![1] as RequestInit).signal?.aborted).toBe(true);
  resolve(Response.json({ content: ["private"] }));
  await rejection;
  resumeSession();
  expect(sessionSignal().aborted).toBe(false);
});
it("announces expired sessions and preserves 403 as permission failure", async () => {
  const expired = vi.fn();
  window.addEventListener("vaultdrive:session-expired", expired);
  try {
    for (const status of [403, 401]) {
      vi.stubGlobal(
        "fetch",
        vi
          .fn()
          .mockResolvedValue(
            Response.json(
              { status, error: "DENIED", message: "Denied" },
              { status },
            ),
          ),
      );
      await expect(
        experienceRequest("activity", new AbortController().signal),
      ).rejects.toHaveProperty("status", status);
    }
    expect(expired).toHaveBeenCalledTimes(1);
    expect(sessionSignal().aborted).toBe(true);
  } finally {
    window.removeEventListener("vaultdrive:session-expired", expired);
  }
});
it("uses exact quota values and does not double count uncertainty", () => {
  const usage = {
    usedBytes: 70n,
    reservedBytes: 10n,
    limitBytes: 100n,
    availableBytes: 20n,
    uncertainStorageBytes: 999n,
    uncertainStorageAllocations: 1n,
    unknownSizeAllocations: 0n,
  };
  expect(quotaWarning(usage)).toContain("80%");
  expect(quotaWarning({ ...usage, reservedBytes: 20n })).toContain("90%");
  expect(quotaWarning({ ...usage, reservedBytes: 30n })).toContain("full");
  expect(quotaWarning({ ...usage, reservedBytes: 31n })).toContain("Over");
  expect(
    quotaWarning({
      ...usage,
      usedBytes: 0n,
      reservedBytes: 0n,
      limitBytes: 0n,
    }),
  ).toContain("zero");
  expect(
    quotaWarning({ ...usage, usedBytes: 0n, reservedBytes: 0n }),
  ).toBeUndefined();
  expect(
    quotaWarning({
      ...usage,
      usedBytes: 9007199254740994n,
      reservedBytes: 0n,
      limitBytes: 10007999171934437n,
    }),
  ).toContain("90%");
});
it("formats timestamps consistently in UTC and tolerates missing instants", () => {
  expect(timestamp("2026-10-26T18:30:00+05:30")).toContain("1:00 PM UTC");
  expect(timestamp("invalid")).toBe("Time unavailable");
});
