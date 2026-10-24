import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/transport";
import {
  ManagementStore,
  controls,
  journal,
  readJournal,
  nameError,
  normalizedName,
  type FileCopy,
  type FolderCopy,
  type Operation,
  type managementRequest,
} from "@/features/management/model";
const fileStatus: FileCopy = {
  id: "op",
  sourceFileId: "source",
  fileId: "independent-copy",
  destinationFolderId: null,
  name: "Copy.txt",
  state: "UNKNOWN",
  revision: 1,
  storageVerified: false,
  createdAt: "2026-10-24T00:00:00Z",
};
const folderStatus: FolderCopy = {
  id: "job",
  sourceFolderId: "source",
  folderId: "hidden-copy",
  destinationFolderId: null,
  name: "Copy",
  state: "ACTIVE",
  checkpoint: 0,
  totalMembers: 1000,
  completedFiles: 0,
  totalFiles: 999,
  revision: 0,
  createdAt: fileStatus.createdAt,
  disposalOperationId: null,
};
let request: ReturnType<typeof vi.fn>;
let changed: ReturnType<typeof vi.fn<() => void>>;
let store: ManagementStore;
beforeEach(() => {
  vi.useFakeTimers();
  request = vi.fn();
  changed = vi.fn();
  store = new ManagementStore({
    request: request as typeof managementRequest,
    changed,
    persist: vi.fn(),
  });
});
afterEach(() => {
  store.stop();
  vi.useRealTimers();
});
it.each([
  "",
  "   ",
  "bad/name",
  "bad\\name",
  "bad\tname",
  "bad\u0085name",
  "x".repeat(256),
  "😀".repeat(128),
])("validates backend-compatible invalid name %j", (value) =>
  expect(nameError(value)).toBeTruthy(),
);
it("uses Java trim, case-sensitive names and UTF-16 length", () => {
  expect(nameError("\u00a0")).toBeUndefined();
  expect(nameError("\u2000")).toBe("Enter a name.");
  expect(nameError("  Notes.txt  ")).toBeUndefined();
  expect(normalizedName("  Notes.txt  ")).toBe("Notes.txt");
  expect(normalizedName("\u00a0name\u00a0")).toBe("\u00a0name\u00a0");
});
it("retains original admission key/request and explicitly replays after loss", async () => {
  request
    .mockRejectedValueOnce(new TypeError("lost"))
    .mockResolvedValueOnce(fileStatus);
  const op = await store.copy("FILE", "source", {
    name: "Copy.txt",
    destinationFolderId: null,
  });
  expect(op.uncertain).toBe(true);
  expect(request).toHaveBeenCalledTimes(1);
  const original = request.mock.calls[0];
  await store.replay(op);
  expect(request.mock.calls[1]).toEqual(original);
  expect(op.id).toBe("op");
  expect(controls(op)).not.toContain("execute");
});
it("labels a confirmed rejected admission distinctly from an uncertain outcome", async () => {
  request
    .mockRejectedValueOnce(
      new ApiError({ status: 409, error: "CONFLICT", message: "Collision" }),
    )
    .mockResolvedValueOnce(fileStatus);
  const op = await store.copy("FILE", "source", { name: "Copy.txt" });
  expect(op.rejected).toBe(true);
  expect(op.uncertain).toBe(false);
  await store.replay(op);
  expect(op.rejected).toBe(false);
  expect(op.status?.state).toBe("UNKNOWN");
});
it("advances bounded folder steps only on explicit continuation and preserves checkpoint", async () => {
  request.mockResolvedValueOnce(folderStatus).mockResolvedValueOnce({
    ...folderStatus,
    checkpoint: 4,
    completedFiles: 3,
  });
  const op = await store.copy("FOLDER", "source", { name: "Copy" });
  expect(request).toHaveBeenCalledTimes(1);
  expect(controls(op)).toContain("execute");
  await store.control(op, "execute");
  expect(op.status).toMatchObject({ checkpoint: 4 });
  expect(request.mock.calls[1]).toEqual([
    "folder-copy-operations/job/execute",
    "POST",
  ]);
});
it("never converts lost execution plus stale ACTIVE read into execute permission", async () => {
  request
    .mockResolvedValueOnce(folderStatus)
    .mockRejectedValueOnce(new Error("lost"))
    .mockResolvedValueOnce(folderStatus)
    .mockResolvedValueOnce({ ...folderStatus, state: "UNKNOWN" });
  const op = await store.copy("FOLDER", "source", { name: "Copy" });
  await store.control(op, "execute");
  await store.check(op);
  expect(op.uncertain).toBe(true);
  expect(controls(op)).not.toContain("execute");
  await store.control(op, "execute");
  expect(request).toHaveBeenCalledTimes(3);
  await store.control(op, "recover");
  expect(request.mock.calls[3]).toEqual([
    "folder-copy-operations/job/recover",
    "POST",
  ]);
});
it("polls GET only with backoff, ten-read bound and cleanup", async () => {
  request.mockResolvedValue(fileStatus);
  await store.copy("FILE", "source", { name: "Copy.txt" });
  await vi.advanceTimersByTimeAsync(1999);
  expect(request).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(1);
  expect(request.mock.calls[1]).toEqual(["copy-operations/op"]);
  await vi.advanceTimersByTimeAsync(600000);
  expect(request).toHaveBeenCalledTimes(11);
  store.stop();
  await vi.advanceTimersByTimeAsync(600000);
  expect(request).toHaveBeenCalledTimes(11);
});
it("stops polling after unmount, including an in-flight response", async () => {
  request.mockResolvedValue(fileStatus);
  const op = await store.copy("FILE", "source", { name: "Copy.txt" });
  let finish!: (value: FileCopy) => void;
  request.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const pending = store.check(op);
  store.stop();
  finish(fileStatus);
  await pending;
  await vi.advanceTimersByTimeAsync(600000);
  expect(request).toHaveBeenCalledTimes(2);
});
it.each(["ACCEPTED", "PROCESSING", "BLOCKED", "FAILED", "LOGICALLY_COMPLETED"])(
  "reads deletion state %s without assuming physical completion",
  async (state) => {
    request
      .mockResolvedValueOnce({ operationId: "deletion" })
      .mockResolvedValueOnce({
        operationId: "deletion",
        state,
        reasonCode: state === "BLOCKED" ? "RETAINED" : null,
      });
    await store.mutate(
      "files/source/permanent",
      "DELETE",
      undefined,
      { name: "Delete", sourceId: "source", sourceType: "FILE" },
      true,
    );
    const op = store.snapshot()[0]!;
    expect(op.status).toBeUndefined();
    expect(op.id).toBe("deletion");
    await store.check(op);
    expect(op.status?.state).toBe(state);
    expect(controls(op)).toEqual([]);
  },
);
it("retains uncertain permanent-delete intent without unsafe acceptance replay", async () => {
  request.mockRejectedValue(new TypeError("lost"));
  await expect(
    store.mutate(
      "files/source/permanent",
      "DELETE",
      undefined,
      { name: "Delete", sourceId: "source", sourceType: "FILE" },
      true,
    ),
  ).rejects.toThrow();
  const op = store.snapshot()[0]!;
  await store.replay(op);
  expect(op.kind).toBe("uncertain");
  expect(op.sourceId).toBe("source");
  expect(request).toHaveBeenCalledTimes(1);
});
it("tracks discard through a separate deletion operation without removing retained copy evidence", async () => {
  request
    .mockResolvedValueOnce({ ...folderStatus, state: "CANCELLED" })
    .mockResolvedValueOnce({
      ...folderStatus,
      state: "CANCELLED",
      disposalOperationId: "disposal",
    });
  const op = await store.copy("FOLDER", "source", { name: "Copy" });
  await store.control(op, "discard");
  expect(store.snapshot()).toHaveLength(2);
  expect(store.snapshot()[1]).toMatchObject({
    kind: "deletion",
    id: "disposal",
  });
  expect(controls(op)).toEqual([]);
});
it("preserves uncertain control after rate limiting and honors Retry-After", async () => {
  request
    .mockResolvedValueOnce(fileStatus)
    .mockRejectedValueOnce(
      new ApiError(
        { status: 429, error: "TOO_MANY_REQUESTS", message: "Busy" },
        "20",
      ),
    );
  const op = await store.copy("FILE", "source", { name: "Copy.txt" });
  await store.control(op, "recover");
  await store.control(op, "recover");
  expect(request).toHaveBeenCalledTimes(2);
  expect(controls(op)).not.toContain("execute");
});
it("distinguishes a denied control and failed status read from a lost mutation", async () => {
  request
    .mockResolvedValueOnce(folderStatus)
    .mockRejectedValueOnce(
      new ApiError({ status: 403, error: "FORBIDDEN", message: "Denied" }),
    )
    .mockRejectedValueOnce(
      new ApiError({
        status: 503,
        error: "SERVICE_UNAVAILABLE",
        message: "Busy",
      }),
    );
  const op = await store.copy("FOLDER", "source", { name: "Copy" });
  await store.control(op, "execute");
  expect(op.uncertain).toBe(false);
  expect(op.error).toContain("permissions");
  await store.check(op);
  expect(op.uncertain).toBe(false);
  expect(controls(op)).toContain("execute");
});
it("rediscovers retained folder disposal through GET after reload", async () => {
  const retained: Operation = {
    localId: "local",
    kind: "folder-copy",
    name: "Copy",
    sourceId: "source",
    sourceType: "FOLDER",
    id: "job",
  };
  store.restore([retained]);
  request.mockResolvedValueOnce({
    ...folderStatus,
    state: "CANCELLED",
    disposalOperationId: "disposal",
  });
  await store.check(store.snapshot()[0]!);
  expect(store.snapshot()[1]).toMatchObject({
    kind: "deletion",
    id: "disposal",
  });
});
it("restores user-scoped evidence without starting mutations or storing credentials/status bigint", async () => {
  const op: Operation = {
    localId: "local",
    kind: "file-copy",
    sourceId: "source",
    sourceType: "FILE",
    name: "Copy",
    id: "op",
    key: "original",
    status: { ...fileStatus, revision: 9007199254740993n },
  };
  const raw = journal([op]);
  expect(raw).not.toContain("revision");
  store.restore(readJournal(raw));
  expect(request).not.toHaveBeenCalled();
  expect(store.snapshot()[0]).toMatchObject({ id: "op", uncertain: true });
  expect(readJournal("bad")).toEqual([]);
});
