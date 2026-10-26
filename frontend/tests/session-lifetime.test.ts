import { beforeEach, expect, it, vi } from "vitest";
import { pauseSession, resumeSession } from "@/features/auth/client-session";
import { getMetadata } from "@/features/explorer/api";
import { transferApi } from "@/features/transfers/api";
import { managementRequest } from "@/features/management/model";
import { sharingRequest } from "@/features/sharing/api";
import { experienceRequest } from "@/features/experience/api";

beforeEach(() => resumeSession());
const calls = [
  ["explorer", () => getMetadata("FILE", "file", new AbortController().signal)],
  ["transfer", () => transferApi.empty({ name: "Empty.txt" }, "original-key")],
  [
    "management",
    () => managementRequest("folders", "POST", { name: "Folder" }),
  ],
  [
    "sharing",
    () =>
      sharingRequest("files/file/grants", "POST", {
        email: "a@example.test",
        role: "VIEWER",
      }),
  ],
  [
    "experience",
    () => experienceRequest("users/me", new AbortController().signal),
  ],
] as const;

it.each(calls)(
  "cancels %s requests and rejects late success after session pause",
  async (_name, call) => {
    let finish!: (value: Response) => void;
    let scope!: AbortSignal;
    vi.stubGlobal(
      "fetch",
      vi.fn((_url, init: RequestInit) => {
        scope = init.signal!;
        return new Promise<Response>((resolve) => {
          finish = resolve;
        });
      }),
    );
    const pending = call();
    const result = expect(pending).rejects.toMatchObject({
      name: "AbortError",
    });
    pauseSession();
    expect(scope.aborted).toBe(true);
    // A response already buffered by a transport must not become UI success.
    finish(Response.json({ name: "late" }));
    await result;
  },
);

it.each(calls)(
  "fences new %s requests while paused without dispatch or replay on resume",
  async (_name, call) => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    pauseSession();
    await expect(call()).rejects.toMatchObject({ name: "AbortError" });
    resumeSession();
    expect(fetcher).not.toHaveBeenCalled();
  },
);

it.each([401, 403, 404, 409, 429, 503])(
  "handles authenticated HTTP %s once, invalidating all tabs only for 401",
  async (status) => {
    const broadcast = vi.fn();
    vi.stubGlobal(
      "BroadcastChannel",
      class {
        postMessage = broadcast;
        close() {}
      },
    );
    const expired = vi.fn();
    window.addEventListener("vaultdrive:session-expired", expired);
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        Response.json(
          { status, error: "TEST", message: "Rejected" },
          { status },
        ),
      );
    vi.stubGlobal("fetch", fetcher);
    try {
      await expect(
        managementRequest("folders", "POST", { name: "Folder" }),
      ).rejects.toMatchObject({ status });
      expect(fetcher).toHaveBeenCalledTimes(1);
      expect(expired).toHaveBeenCalledTimes(status === 401 ? 1 : 0);
      expect(broadcast.mock.calls).toEqual(status === 401 ? [["ended"]] : []);
    } finally {
      window.removeEventListener("vaultdrive:session-expired", expired);
    }
  },
);
