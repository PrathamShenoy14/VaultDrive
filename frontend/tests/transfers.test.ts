// @vitest-environment node
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  UploadManager,
  journal,
  restoreJournal,
} from "@/features/transfers/manager";
import {
  PART_SIZE,
  type TransferApi,
  type UploadSession,
  type StoredPart,
  type PartUrl,
  type EmptyCreation,
} from "@/features/transfers/contracts";
import {
  checksum,
  matchEvidence,
  signedHeaders,
  TransferProblem,
} from "@/features/transfers/storage";
import { ApiError } from "@/lib/api/transport";

const uuid = "11111111-1111-4111-8111-111111111111";
const digest = "A".repeat(43) + "=";
const managers: UploadManager[] = [];
afterEach(() => {
  managers.forEach((manager) => manager.stop());
  managers.length = 0;
});
function session(
  size: number,
  state: UploadSession["state"] = "OPEN",
): UploadSession {
  return {
    id: uuid,
    fileId: uuid,
    state,
    revision: 0,
    initiationId: uuid,
    uploadId: "opaque",
    decisionId: null,
    createdAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 86400000).toISOString(),
    declaredSize: size,
    partSize: PART_SIZE,
    partCount: Math.ceil(size / PART_SIZE),
    completion: null,
    ownerId: uuid,
    initiatorUserId: uuid,
  };
}
function fixture(size = 3, overrides: Partial<TransferApi> = {}) {
  let current = session(size, "RESERVED");
  const stored: StoredPart[] = [];
  const api: TransferApi = {
    reserve: vi.fn(async () => current),
    session: vi.fn(async () => current),
    initiate: vi.fn(async () => (current = { ...current, state: "OPEN" })),
    sign: vi.fn(async (_id, part) => [
      {
        part: { ...part, registrationId: uuid },
        upload: {
          url: "https://storage.example.test/part?capability=secret",
          expiresAt: new Date(Date.now() + 300000).toISOString(),
          requiredHeaders: {
            host: ["storage.example.test"],
            "content-length": [String(part.length)],
            "x-amz-checksum-sha256": [part.sha256],
          },
        },
      },
    ]),
    parts: vi.fn(async (_id, marker, limit) => {
      const available = stored.filter((p) => p.partNumber > marker);
      const parts = available.slice(0, limit);
      return {
        parts,
        nextMarker: available.length > limit ? parts.at(-1)!.partNumber : null,
      };
    }),
    complete: vi.fn(
      async () =>
        (current = {
          ...current,
          state: "COMPLETION_DECIDED",
          completion: {
            disposition: "READY",
            revision: 0,
            dispatchId: uuid,
            receiptRecorded: true,
            reason: null,
          },
        }),
    ),
    recover: vi.fn(async () => current),
    cancel: vi.fn(async () => ({
      session: (current = { ...current, state: "ABANDONMENT_DECIDED" }),
      cause: "USER",
      disposition: "CANCELLED" as const,
      reason: null,
      dispatchId: uuid,
      revision: 0,
      evidenceKind: "ACK",
      acknowledgedAt: new Date().toISOString(),
    })),
    cancellation: vi.fn(),
    recoverCancellation: vi.fn(),
    empty: vi.fn(async () => ({
      id: uuid,
      fileId: uuid,
      state: "READY" as const,
      storageVerified: true,
    })),
    emptyStatus: vi.fn(),
    recoverEmpty: vi.fn(),
    ...overrides,
  };
  const put = vi.fn(async (_url, part, bytes, _signal, progress) => {
    progress(bytes.byteLength / 2);
    progress(bytes.byteLength);
    stored.push({
      partNumber: part.partNumber,
      size: part.length,
      sha256: part.sha256,
      etag: `"opaque-${part.partNumber}"`,
    });
    return { etag: `"opaque-${part.partNumber}"`, checksum: part.sha256 };
  });
  const changed = vi.fn(),
    persist = vi.fn();
  const manager = new UploadManager({ api, put, changed, persist });
  managers.push(manager);
  const file = new File([new Uint8Array(size)], "data.bin", {
    type: "application/octet-stream",
    lastModified: 123,
  });
  return {
    api,
    manager,
    put,
    file,
    stored,
    changed,
    persist,
    setSession: (s: UploadSession) => (current = s),
  };
}
async function idle(manager: UploadManager) {
  await vi.waitFor(() =>
    expect(
      manager.snapshot().every((t) => !t.busy && t.state !== "queued"),
    ).toBe(true),
  );
}

describe("M3 upload state machine (mocked control and storage)", () => {
  it("does not start completion recovery after shutdown during a status read", async () => {
    const f = fixture();
    f.put.mockRejectedValue(new TransferProblem("Interrupted", "paused"));
    f.manager.add([f.file], null);
    await idle(f.manager);
    let finish!: (value: UploadSession) => void;
    vi.mocked(f.api.session).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    f.manager.act(f.manager.snapshot()[0]!.id, "recover");
    await vi.waitFor(() => expect(finish).toBeDefined());
    f.manager.stop();
    finish({
      ...session(3, "COMPLETION_DECIDED"),
      completion: {
        disposition: "UNKNOWN",
        revision: 1,
        dispatchId: uuid,
        receiptRecorded: false,
        reason: null,
      },
    });
    await new Promise<void>((resolve) => setImmediate(resolve));
    expect(f.api.recover).not.toHaveBeenCalled();
  });
  it("does not start cancellation after the workspace closes during its status read", async () => {
    const f = fixture();
    f.put.mockRejectedValue(new TransferProblem("Interrupted", "paused"));
    f.manager.add([f.file], null);
    await idle(f.manager);
    let release!: (value: UploadSession) => void;
    vi.mocked(f.api.session).mockImplementationOnce(
      () =>
        new Promise<UploadSession>((resolve) => {
          release = resolve;
        }),
    );
    f.manager.cancel(f.manager.snapshot()[0]!.id);
    await vi.waitFor(() => expect(f.api.session).toHaveBeenCalled());
    f.manager.stop();
    release(session(3));
    await new Promise<void>((resolve) => setImmediate(resolve));
    expect(f.api.cancel).not.toHaveBeenCalled();
  });
  it.each(["initiate", "complete", "cancel"] as const)(
    "keeps uncertain %s evidence when a later status read receives 429",
    async (action) => {
      const f = fixture();
      vi.mocked(f.api[action]).mockRejectedValueOnce(
        new TypeError("Control response lost"),
      );
      if (action === "cancel")
        f.put.mockRejectedValue(new TransferProblem("Interrupted", "paused"));
      f.manager.add([f.file], null);
      await idle(f.manager);
      const id = f.manager.snapshot()[0]!.id;
      if (action === "cancel") {
        f.manager.cancel(id);
        await idle(f.manager);
      }
      expect(f.manager.snapshot()[0]!.dispatch).toBe(action);
      expect(f.manager.snapshot()[0]!.state).toBe("unknown");
      vi.mocked(f.api.session).mockRejectedValueOnce(
        new ApiError({
          status: 429,
          error: "TOO_MANY_REQUESTS",
          message: "Busy",
        }),
      );
      f.manager.act(id, "check");
      await idle(f.manager);
      expect(f.manager.snapshot()[0]!.dispatch).toBe(action);
      if (action === "cancel") f.manager.cancel(id);
      else f.manager.act(id, "run");
      await idle(f.manager);
      expect(f.api[action]).toHaveBeenCalledTimes(1);
    },
  );
  it.each([0, 3])(
    "preserves a previously lost %i-byte admission when its explicit replay is rate limited",
    async (size) => {
      const f = fixture(size),
        admit = size === 0 ? f.api.empty : f.api.reserve;
      vi.mocked(admit)
        .mockRejectedValueOnce(new TypeError("Lost admission"))
        .mockRejectedValueOnce(
          new ApiError({
            status: 429,
            error: "TOO_MANY_REQUESTS",
            message: "Busy",
          }),
        );
      f.manager.add([f.file], null);
      await idle(f.manager);
      const id = f.manager.snapshot()[0]!.id;
      f.manager.act(id, "run");
      await idle(f.manager);
      expect(f.manager.snapshot()[0]!.dispatch).toBe(
        size === 0 ? "empty" : "admission",
      );
      f.manager.cancel(id);
      await idle(f.manager);
      expect(f.manager.snapshot()[0]!.state).not.toBe("cancelled");
      expect(admit).toHaveBeenCalledTimes(2);
    },
  );
  it("allows explicit initiation retry only when that initiation received known 429 admission rejection", async () => {
    const f = fixture();
    vi.mocked(f.api.initiate).mockRejectedValueOnce(
      new ApiError({
        status: 429,
        error: "TOO_MANY_REQUESTS",
        message: "Busy",
      }),
    );
    f.manager.add([f.file], null);
    await idle(f.manager);
    expect(f.manager.snapshot()[0]!.dispatch).toBeUndefined();
    f.manager.act(f.manager.snapshot()[0]!.id, "run");
    await idle(f.manager);
    expect(f.manager.snapshot()[0]!.state).toBe("completed");
    expect(f.api.initiate).toHaveBeenCalledTimes(2);
  });
  it.each([3, PART_SIZE * 2 + 37])(
    "uploads %i bytes in bounded slices with exact evidence and READY publication",
    async (size) => {
      const f = fixture(size),
        progress: number[] = [];
      f.manager.subscribe(() => progress.push(f.manager.snapshot()[0]!.bytes));
      f.manager.add([f.file], null);
      await idle(f.manager);
      expect(f.manager.snapshot()[0]).toMatchObject({
        state: "completed",
        bytes: size,
        evidence: f.stored,
      });
      expect(f.put).toHaveBeenCalledTimes(Math.ceil(size / PART_SIZE));
      for (const call of f.put.mock.calls)
        expect(call[2].byteLength).toBeLessThanOrEqual(PART_SIZE);
      expect(progress.some((value) => value > 0 && value < size)).toBe(true);
      expect(f.api.complete).toHaveBeenCalledTimes(1);
      expect(f.changed).toHaveBeenCalled();
      expect(JSON.stringify(f.persist.mock.calls)).not.toContain("capability");
      expect(f.api.reserve).toHaveBeenCalledWith(
        expect.objectContaining({ size }),
        expect.any(String),
      );
    },
  );
  it("uses the empty-file API without multipart or a browser PUT", async () => {
    const f = fixture(0);
    f.manager.add([f.file], null);
    await idle(f.manager);
    expect(f.manager.snapshot()[0]!.state).toBe("completed");
    expect(f.api.empty).toHaveBeenCalledTimes(1);
    expect(f.api.reserve).not.toHaveBeenCalled();
    expect(f.put).not.toHaveBeenCalled();
  });
  it("replays only known RESERVED empty intent with its original key", async () => {
    const f = fixture(0);
    vi.mocked(f.api.empty).mockResolvedValueOnce({
      id: uuid,
      fileId: uuid,
      state: "RESERVED",
      storageVerified: false,
    });
    f.manager.add([f.file], null);
    await idle(f.manager);
    f.manager.act(f.manager.snapshot()[0]!.id, "run");
    await idle(f.manager);
    expect(f.manager.snapshot()[0]!.state).toBe("completed");
    expect(vi.mocked(f.api.empty).mock.calls[0]).toEqual(
      vi.mocked(f.api.empty).mock.calls[1],
    );
  });
  it("bounds polling to ten GETs without automatically recovering or creating again", async () => {
    vi.useFakeTimers();
    const unknown: EmptyCreation = {
      id: uuid,
      fileId: uuid,
      state: "UNKNOWN",
      storageVerified: false,
    };
    const f = fixture(0, {
      empty: vi.fn(async () => unknown),
      emptyStatus: vi.fn(async () => unknown),
    });
    try {
      f.manager.add([f.file], null);
      await vi.advanceTimersByTimeAsync(300000);
      expect(f.api.emptyStatus).toHaveBeenCalledTimes(10);
      expect(f.api.empty).toHaveBeenCalledTimes(1);
      expect(f.api.recoverEmpty).not.toHaveBeenCalled();
      expect(f.manager.snapshot()[0]!.state).toBe("unknown");
    } finally {
      f.manager.stop();
      vi.useRealTimers();
    }
  });
  it("blocks unfamiliar completion states and offers no recovery dispatch", async () => {
    const f = fixture();
    const unfamiliar = {
      ...session(3, "COMPLETION_DECIDED"),
      completion: {
        disposition: "FUTURE_STATE",
        revision: 0,
        dispatchId: null,
        receiptRecorded: false,
        reason: null,
      },
    } as unknown as UploadSession;
    f.setSession(unfamiliar);
    f.manager.restore([
      {
        id: uuid,
        key: uuid,
        name: "data.bin",
        size: 3,
        contentType: "application/octet-stream",
        lastModified: 123,
        folderId: null,
        sessionId: uuid,
      },
    ]);
    f.manager.act(uuid, "recover");
    await idle(f.manager);
    expect(f.manager.snapshot()[0]!.state).toBe("blocked");
    expect(f.api.recover).not.toHaveBeenCalled();
    expect(f.api.complete).not.toHaveBeenCalled();
  });
  it("retains UNKNOWN empty creation and uses explicit recovery without another PUT", async () => {
    const f = fixture(0, {
      empty: vi.fn(async () => ({
        id: uuid,
        fileId: uuid,
        state: "UNKNOWN" as const,
        storageVerified: false,
      })),
      recoverEmpty: vi.fn(async () => ({
        id: uuid,
        fileId: uuid,
        state: "READY" as const,
        storageVerified: true,
      })),
    });
    f.manager.add([f.file], null);
    await idle(f.manager);
    const id = f.manager.snapshot()[0]!.id;
    expect(f.manager.snapshot()[0]!.state).toBe("unknown");
    f.manager.cancel(id);
    expect(f.api.cancel).not.toHaveBeenCalled();
    f.manager.act(id, "recover");
    await idle(f.manager);
    expect(f.manager.snapshot()[0]!.state).toBe("completed");
    expect(f.api.empty).toHaveBeenCalledTimes(1);
  });
  it.each(["STORAGE_COMPLETE", "FAILED", "REMOVED"] as const)(
    "does not confuse empty %s with completion",
    async (state) => {
      const f = fixture(0, {
        empty: vi.fn(async () => ({
          id: uuid,
          fileId: uuid,
          state,
          storageVerified: false,
        })),
      });
      f.manager.add([f.file], null);
      await idle(f.manager);
      expect(f.manager.snapshot()[0]!.state).toBe(
        state === "STORAGE_COMPLETE" ? "blocked" : "failed",
      );
    },
  );
  it("bounds concurrent files to two and queues the rest", async () => {
    const admissions: (() => void)[] = [];
    const f = fixture(0, {
      empty: vi.fn(
        () =>
          new Promise<EmptyCreation>((resolve) =>
            admissions.push(() =>
              resolve({
                id: uuid,
                fileId: uuid,
                state: "READY",
                storageVerified: true,
              }),
            ),
          ),
      ),
    });
    f.manager.add([f.file, f.file, f.file], null);
    await vi.waitFor(() => expect(f.api.empty).toHaveBeenCalledTimes(2));
    expect(
      f.manager.snapshot().filter((t) => t.state === "queued"),
    ).toHaveLength(1);
    admissions[0]!();
    await vi.waitFor(() => expect(f.api.empty).toHaveBeenCalledTimes(3));
    admissions[1]!();
    admissions[2]!();
    await idle(f.manager);
  });
  it("cancels a queued file locally and waits for admission before cancelling active work", async () => {
    let release!: () => void;
    const f = fixture(3);
    vi.mocked(f.api.reserve).mockImplementation(
      () =>
        new Promise(
          (resolve) => (release = () => resolve(session(3, "RESERVED"))),
        ),
    );
    f.manager.add([f.file], null);
    await vi.waitFor(() => expect(f.api.reserve).toHaveBeenCalled());
    f.manager.cancel(f.manager.snapshot()[0]!.id);
    expect(f.api.cancel).not.toHaveBeenCalled();
    release();
    await idle(f.manager);
    expect(f.manager.snapshot()[0]!.state).toBe("cancelled");
    expect(f.api.cancel).toHaveBeenCalledTimes(1);
    expect(f.api.initiate).not.toHaveBeenCalled();
    expect(f.manager.snapshot()[0]!.message).toContain(
      "does not assert physical",
    );
  });
  it("aborts an active part then inspects and records cancellation once", async () => {
    const f = fixture();
    f.put.mockImplementation(
      (_url, _part, _bytes, signal) =>
        new Promise((_resolve, reject) =>
          signal.addEventListener("abort", () =>
            reject(new TransferProblem("Stopped", "paused")),
          ),
        ),
    );
    f.manager.add([f.file], null);
    await vi.waitFor(() => expect(f.put).toHaveBeenCalled());
    f.manager.cancel(f.manager.snapshot()[0]!.id);
    await idle(f.manager);
    expect(f.manager.snapshot()[0]!.state).toBe("cancelled");
    expect(f.api.cancel).toHaveBeenCalledTimes(1);
    expect(f.api.complete).not.toHaveBeenCalled();
  });
  it("inspects authoritative parts after a lost PUT response and skips acknowledged bytes", async () => {
    const f = fixture();
    const successfulPut = f.put.getMockImplementation()!;
    f.put.mockImplementationOnce(async (...args) => {
      await successfulPut(...args);
      throw new TransferProblem("Response lost", "paused");
    });
    f.manager.add([f.file], null);
    await idle(f.manager);
    expect(f.manager.snapshot()[0]!.state).toBe("paused");
    f.manager.act(f.manager.snapshot()[0]!.id, "run");
    await idle(f.manager);
    expect(f.manager.snapshot()[0]!.state).toBe("completed");
    expect(f.put).toHaveBeenCalledTimes(1);
  });
  it("requires explicit resume to refresh an expired URL and preserves immutable expectations", async () => {
    const f = fixture();
    f.put.mockRejectedValueOnce(new TransferProblem("URL expired", "paused"));
    f.manager.add([f.file], null);
    await idle(f.manager);
    expect(f.api.sign).toHaveBeenCalledTimes(1);
    f.manager.act(f.manager.snapshot()[0]!.id, "run");
    await idle(f.manager);
    expect(f.api.sign).toHaveBeenCalledTimes(2);
    expect(vi.mocked(f.api.sign).mock.calls[0]![1]).toEqual(
      vi.mocked(f.api.sign).mock.calls[1]![1],
    );
    expect(f.manager.snapshot()[0]!.state).toBe("completed");
  });
  it("never redispatches an uncertain initiation", async () => {
    const f = fixture();
    vi.mocked(f.api.initiate).mockImplementation(async () => {
      f.setSession(session(3, "INITIATING"));
      throw new TypeError("lost");
    });
    f.manager.add([f.file], null);
    await idle(f.manager);
    const id = f.manager.snapshot()[0]!.id;
    f.manager.act(id, "run");
    await idle(f.manager);
    f.manager.act(id, "recover");
    await idle(f.manager);
    expect(f.manager.snapshot()[0]!.state).toBe("unknown");
    expect(f.api.initiate).toHaveBeenCalledTimes(1);
    expect(f.put).not.toHaveBeenCalled();
  });
  it("never resends uncertain complete and recovers only its retained decision", async () => {
    const f = fixture();
    const pending = {
      ...session(3, "COMPLETION_DECIDED"),
      completion: {
        disposition: "UNKNOWN" as const,
        revision: 1,
        dispatchId: uuid,
        receiptRecorded: false,
        reason: null,
      },
    };
    vi.mocked(f.api.complete).mockImplementation(async () => {
      f.setSession(pending);
      throw new TypeError("lost");
    });
    vi.mocked(f.api.recover).mockResolvedValue({
      ...pending,
      completion: {
        ...pending.completion,
        disposition: "READY",
        receiptRecorded: true,
      },
    });
    f.manager.add([f.file], null);
    await idle(f.manager);
    const id = f.manager.snapshot()[0]!.id;
    expect(f.manager.snapshot()[0]!.state).toBe("unknown");
    f.manager.act(id, "run");
    await idle(f.manager);
    f.manager.act(id, "recover");
    await idle(f.manager);
    expect(f.manager.snapshot()[0]!.state).toBe("completed");
    expect(f.api.complete).toHaveBeenCalledTimes(1);
    expect(f.api.recover).toHaveBeenCalledTimes(1);
  });
  it("preserves uncertain cancellation without a second Abort", async () => {
    const f = fixture();
    f.put.mockRejectedValue(new TransferProblem("Interrupted", "paused"));
    vi.mocked(f.api.cancel).mockRejectedValue(new TypeError("lost"));
    f.manager.add([f.file], null);
    await idle(f.manager);
    const id = f.manager.snapshot()[0]!.id;
    f.manager.cancel(id);
    await idle(f.manager);
    f.manager.cancel(id);
    await idle(f.manager);
    expect(f.api.cancel).toHaveBeenCalledTimes(1);
    expect(f.api.complete).not.toHaveBeenCalled();
  });
  it.each([401, 403, 404, 409, 429])(
    "presents authoritative HTTP %i with no automatic mutation retry",
    async (status) => {
      const f = fixture(3, {
        reserve: vi.fn(async () => {
          throw new ApiError(
            { status, error: "ERROR", message: "Rejected" },
            status === 429 ? "1" : null,
          );
        }),
      });
      f.manager.add([f.file], null);
      await idle(f.manager);
      expect(f.api.reserve).toHaveBeenCalledTimes(1);
      expect(f.put).not.toHaveBeenCalled();
      expect(f.manager.snapshot()[0]!.state).toBe(
        status === 429 ? "paused" : "blocked",
      );
      if (status === 409) expect(f.changed).toHaveBeenCalled();
    },
  );
  it("recovers lost admission using the same key and never silently allocates a new file", async () => {
    const f = fixture();
    vi.mocked(f.api.reserve).mockRejectedValueOnce(new TypeError("lost"));
    f.manager.add([f.file], null);
    await idle(f.manager);
    f.manager.act(f.manager.snapshot()[0]!.id, "run");
    await idle(f.manager);
    expect(f.manager.snapshot()[0]!.state).toBe("completed");
    expect(vi.mocked(f.api.reserve).mock.calls[0]).toEqual(
      vi.mocked(f.api.reserve).mock.calls[1],
    );
  });
  it("restores only safe handles, requires original file and validates provider checksums", async () => {
    const f = fixture();
    f.put.mockRejectedValue(new TransferProblem("offline", "paused"));
    f.manager.add([f.file], null);
    await idle(f.manager);
    const records = restoreJournal(
      JSON.stringify(journal(f.manager.snapshot())),
    );
    expect(JSON.stringify(records)).not.toMatch(
      /url|uploadId|accessToken|evidence/,
    );
    const restored = fixture();
    restored.setSession(session(3));
    restored.manager.restore(records);
    expect(restored.api.session).not.toHaveBeenCalled();
    expect(
      restored.manager.attach(records[0]!.id, new File(["bad"], "other.bin")),
    ).toContain("original");
    expect(restored.manager.attach(records[0]!.id, f.file)).toBeUndefined();
    restored.stored.push({
      partNumber: 1,
      size: 3,
      sha256: digest,
      etag: "opaque",
    });
    restored.manager.act(records[0]!.id, "run");
    await idle(restored.manager);
    expect(restored.manager.snapshot()[0]!.state).toBe("blocked");
    expect(restored.api.complete).not.toHaveBeenCalled();
  });
  it("fails closed on nonadvancing provider pagination", async () => {
    const f = fixture(3, {
      parts: vi.fn(async () => ({ parts: [], nextMarker: 1 })),
    });
    f.manager.add([f.file], null);
    await idle(f.manager);
    expect(f.manager.snapshot()[0]!.state).toBe("blocked");
    expect(f.put).not.toHaveBeenCalled();
  });
  it("resumes after reload with the original file and sends only the missing part", async () => {
    const size = PART_SIZE + 3,
      original = fixture(size);
    original.put
      .mockImplementationOnce(original.put.getMockImplementation()!)
      .mockRejectedValueOnce(new TransferProblem("Disconnected", "paused"));
    original.manager.add([original.file], null);
    await idle(original.manager);
    expect(original.stored).toHaveLength(1);
    const records = restoreJournal(
      JSON.stringify(journal(original.manager.snapshot())),
    );
    const restored = fixture(size);
    restored.setSession(session(size));
    restored.stored.push(...original.stored);
    restored.manager.restore(records);
    expect(
      restored.manager.attach(records[0]!.id, original.file),
    ).toBeUndefined();
    restored.manager.act(records[0]!.id, "run");
    await idle(restored.manager);
    expect(restored.manager.snapshot()[0]!.state).toBe("completed");
    expect(restored.put).toHaveBeenCalledTimes(1);
    expect(restored.put.mock.calls[0]![1].partNumber).toBe(2);
    expect(restored.api.initiate).not.toHaveBeenCalled();
    expect(restored.api.reserve).not.toHaveBeenCalled();
  });
  it("cancels a queued empty file before admission without affecting active files", async () => {
    const releases: (() => void)[] = [];
    const f = fixture(0, {
      empty: vi.fn(
        () =>
          new Promise<EmptyCreation>((resolve) =>
            releases.push(() =>
              resolve({
                id: uuid,
                fileId: uuid,
                state: "READY",
                storageVerified: true,
              }),
            ),
          ),
      ),
    });
    f.manager.add([f.file, f.file, f.file], null);
    expect(f.api.empty).toHaveBeenCalledTimes(2);
    const queued = f.manager.snapshot()[2]!;
    f.manager.cancel(queued.id);
    expect(f.manager.snapshot()[2]!.state).toBe("cancelled");
    expect(f.api.cancel).not.toHaveBeenCalled();
    releases.forEach((release) => release());
    await idle(f.manager);
    expect(f.api.empty).toHaveBeenCalledTimes(2);
    expect(
      f.manager.snapshot().filter((t) => t.state === "completed"),
    ).toHaveLength(2);
  });
});

describe("M3 signed capabilities and exact evidence", () => {
  const part = { partNumber: 1, length: 3, sha256: digest };
  function signed(): PartUrl {
    return {
      part: { ...part, registrationId: uuid },
      upload: {
        url: "https://garage.example.test/bucket/key?signature=private",
        expiresAt: new Date(Date.now() + 300000).toISOString(),
        requiredHeaders: {
          host: ["garage.example.test"],
          "content-length": ["3"],
          "x-amz-checksum-sha256": [digest],
        },
      },
    };
  }
  it("sends only checksum and preserves forbidden-header framing", () => {
    expect(signedHeaders(signed(), part)).toEqual({
      "x-amz-checksum-sha256": digest,
    });
  });
  it.each([
    "http",
    "host",
    "length",
    "digest",
    "extra",
    "duplicate",
    "expired",
  ])("rejects %s mismatch without a storage dispatch", (change) => {
    const value = signed();
    if (change === "http")
      value.upload.url = value.upload.url.replace("https:", "http:");
    if (change === "host") value.upload.requiredHeaders.host = ["other.test"];
    if (change === "length")
      value.upload.requiredHeaders["content-length"] = ["4"];
    if (change === "digest") value.part.sha256 = "changed";
    if (change === "extra")
      value.upload.requiredHeaders.Authorization = ["private"];
    if (change === "duplicate")
      value.upload.requiredHeaders.host!.push("other");
    if (change === "expired")
      value.upload.expiresAt = new Date(Date.now() - 1000).toISOString();
    expect(() => signedHeaders(value, part)).toThrow(TransferProblem);
  });
  it("keeps ETag quotes and rejects size, hash or ETag mismatch", () => {
    const observed = {
      partNumber: 1,
      size: 3,
      sha256: digest,
      etag: '"opaque"',
    };
    expect(() =>
      matchEvidence(observed, part, { etag: '"opaque"', checksum: digest }),
    ).not.toThrow();
    expect(() =>
      matchEvidence(observed, part, { etag: "opaque", checksum: digest }),
    ).toThrow();
    expect(() => matchEvidence({ ...observed, size: 4 }, part)).toThrow();
  });
  it("computes canonical Base64 SHA-256", async () => {
    expect(await checksum(new TextEncoder().encode("abc").buffer)).toBe(
      "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=",
    );
  });
});
