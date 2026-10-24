import { ApiError, errorMessage } from "@/lib/api/transport";
import { transferApi } from "./api";
import {
  checksum,
  matchEvidence,
  putPart,
  TransferProblem,
  validateSession,
} from "./storage";
import {
  FILE_CONCURRENCY,
  MAX_FILE_SIZE,
  PART_SIZE,
  TRANSFER_LIMIT,
  UUID,
  canRecoverCompletion,
  type Transfer,
  type TransferApi,
  type TransferRecord,
  type UploadSession,
  type StoredPart,
  type Cancellation,
  type EmptyCreation,
  type Dispatch,
} from "./contracts";

type Action = "run" | "check" | "recover" | "cancel";
type Dependencies = {
  api: TransferApi;
  hash: typeof checksum;
  put: typeof putPart;
  changed: () => void;
  persist: (records: TransferRecord[]) => void;
};
const terminal = (t: Transfer) => ["completed", "cancelled"].includes(t.state);
export function journal(transfers: readonly Transfer[]): TransferRecord[] {
  return transfers
    .filter((t) => !terminal(t))
    .map(
      ({
        id,
        key,
        name,
        size,
        contentType,
        lastModified,
        folderId,
        sessionId,
        emptyId,
        dispatch,
      }) => ({
        id,
        key,
        name,
        size,
        contentType,
        lastModified,
        folderId,
        sessionId,
        emptyId,
        dispatch,
      }),
    );
}
export function restoreJournal(raw: string | null): TransferRecord[] {
  try {
    const records: unknown = JSON.parse(raw ?? "[]");
    if (!Array.isArray(records) || records.length > TRANSFER_LIMIT) return [];
    return records
      .filter(
        (v): v is TransferRecord =>
          !!v &&
          UUID.test(v.id) &&
          UUID.test(v.key) &&
          typeof v.name === "string" &&
          v.name.length <= 255 &&
          Number.isSafeInteger(v.size) &&
          v.size >= 0 &&
          v.size <= MAX_FILE_SIZE &&
          typeof v.contentType === "string" &&
          v.contentType.length <= 255 &&
          Number.isSafeInteger(v.lastModified) &&
          (v.folderId === null || UUID.test(v.folderId)) &&
          (!v.sessionId || UUID.test(v.sessionId)) &&
          (!v.emptyId || UUID.test(v.emptyId)) &&
          (!v.dispatch ||
            ["admission", "initiate", "complete", "cancel", "empty"].includes(
              v.dispatch,
            )),
      )
      .map(
        ({
          id,
          key,
          name,
          size,
          contentType,
          lastModified,
          folderId,
          sessionId,
          emptyId,
          dispatch,
        }) => ({
          id,
          key,
          name,
          size,
          contentType,
          lastModified,
          folderId,
          sessionId,
          emptyId,
          dispatch,
        }),
      );
  } catch {
    return [];
  }
}

export class UploadManager {
  private transfers: Transfer[] = [];
  private listeners = new Set<() => void>();
  private files = new Map<string, File>();
  private controllers = new Map<string, AbortController>();
  private cancellations = new Set<string>();
  private work: { id: string; action: Action }[] = [];
  private timers = new Map<string, ReturnType<typeof setTimeout>>();
  private polls = new Map<string, number>();
  private active = 0;
  private stopped = false;
  private deps: Dependencies;

  constructor(deps: Partial<Dependencies> = {}) {
    this.deps = {
      api: transferApi,
      hash: checksum,
      put: putPart,
      changed: () => {},
      persist: () => {},
      ...deps,
    };
  }
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  snapshot = () => this.transfers;
  private emit() {
    this.transfers = [...this.transfers];
    this.deps.persist(journal(this.transfers));
    this.listeners.forEach((listener) => listener());
  }
  private patch(t: Transfer, update: Partial<Transfer>) {
    Object.assign(t, update);
    if (!this.stopped) this.emit();
  }
  restore(records: TransferRecord[]) {
    if (this.transfers.length) return;
    this.transfers = records.map((record) => ({
      ...record,
      state: "paused",
      bytes: 0,
      message:
        "Retained transfer. Check status, then reselect the original file to resume. No storage operation was repeated.",
      busy: false,
      hasFile: false,
      evidence: [],
    }));
    this.emit();
  }
  add(files: File[], folderId: string | null): string | undefined {
    if (this.stopped) return "Transfers are unavailable.";
    if (this.transfers.length + files.length > TRANSFER_LIMIT)
      return `Keep at most ${TRANSFER_LIMIT} transfers in this panel. Dismiss finished transfers before adding more.`;
    for (const file of files) {
      const id = crypto.randomUUID();
      const invalid =
        !file.name.trim() ||
        file.name.trim().length > 255 ||
        /[\\/\u0000-\u001f\u007f]/.test(file.name) ||
        file.size > MAX_FILE_SIZE;
      const t: Transfer = {
        id,
        key: crypto.randomUUID(),
        name: file.name,
        size: file.size,
        contentType: file.type || "application/octet-stream",
        lastModified: file.lastModified,
        folderId,
        state: invalid ? "failed" : "queued",
        bytes: 0,
        message: invalid
          ? "Use a valid filename of at most 255 characters and a file no larger than 5 GiB."
          : "Waiting to upload",
        busy: false,
        hasFile: !invalid,
        evidence: [],
      };
      this.transfers.push(t);
      if (!invalid) {
        this.files.set(id, file);
        this.work.push({ id, action: "run" });
      }
    }
    this.emit();
    this.pump();
  }
  attach(id: string, file: File): string | undefined {
    const t = this.find(id);
    if (!t || t.busy || terminal(t))
      return "This transfer cannot accept a file now.";
    if (
      file.name !== t.name ||
      file.size !== t.size ||
      file.lastModified !== t.lastModified ||
      (file.type || "application/octet-stream") !== t.contentType
    )
      return "Reselect the original file with the same name, size, type and modification time. Registered checksums are verified before resuming.";
    this.files.set(id, file);
    this.patch(t, {
      hasFile: true,
      message:
        "Original file selected. Resume will verify the registered bytes.",
    });
  }
  act(id: string, action: Action) {
    const t = this.find(id);
    if (!t || t.busy || terminal(t) || (t.retryAt && Date.now() < t.retryAt))
      return;
    this.clearTimer(id);
    this.polls.set(id, 0);
    this.work.push({ id, action });
    this.patch(t, { busy: true });
    this.pump();
  }
  cancel(id: string) {
    const t = this.find(id);
    if (
      !t ||
      terminal(t) ||
      t.dispatch === "complete" ||
      t.session?.completion ||
      (t.size === 0 && (t.dispatch || t.emptyId))
    )
      return;
    this.clearTimer(id);
    this.cancellations.add(id);
    this.controllers.get(id)?.abort();
    if (t.busy) {
      this.patch(t, {
        message:
          "Stopping local transfer; waiting for the current backend response before inspecting cancellation.",
      });
      return;
    }
    this.work = this.work.filter((job) => job.id !== id);
    if (!t.sessionId && !t.dispatch) {
      this.markCancelled(t);
      return;
    }
    this.act(id, "cancel");
  }
  dismiss(id: string) {
    const t = this.find(id);
    if (
      !t ||
      t.busy ||
      !(
        terminal(t) ||
        (t.state === "failed" && !t.dispatch && !t.sessionId && !t.emptyId)
      )
    )
      return;
    this.clearTimer(id);
    this.files.delete(id);
    this.transfers = this.transfers.filter((v) => v.id !== id);
    this.emit();
  }
  stop() {
    this.stopped = true;
    this.controllers.forEach((c) => c.abort());
    this.timers.forEach(clearTimeout);
    this.timers.clear();
    this.work = [];
    this.files.clear();
    this.listeners.clear();
  }
  private find(id: string) {
    return this.transfers.find((t) => t.id === id);
  }
  private clearTimer(id: string) {
    clearTimeout(this.timers.get(id));
    this.timers.delete(id);
  }
  private pump() {
    while (
      !this.stopped &&
      this.active < FILE_CONCURRENCY &&
      this.work.length
    ) {
      const job = this.work.shift()!,
        t = this.find(job.id);
      if (!t || terminal(t) || this.controllers.has(t.id)) continue;
      this.active++;
      const controller = new AbortController();
      this.controllers.set(t.id, controller);
      this.patch(t, { busy: true });
      void this.execute(t, job.action, controller.signal)
        .catch((error: unknown) => this.problem(t, error))
        .finally(async () => {
          // Cancellation waits for admission/initiation acknowledgement. It never
          // races a control request or retries an uncertain Abort.
          if (!this.stopped && this.cancellations.has(t.id) && !terminal(t)) {
            this.cancellations.delete(t.id);
            try {
              await this.cancelRemote(t);
            } catch (error) {
              this.problem(t, error);
            }
          }
          this.controllers.delete(t.id);
          this.active--;
          if (!this.stopped) {
            this.patch(t, { busy: false });
            this.poll(t);
            this.pump();
          }
        });
    }
  }
  private async execute(t: Transfer, action: Action, signal: AbortSignal) {
    if (this.stopped) return;
    if (this.cancellations.has(t.id) && action !== "cancel") return;
    if (action === "cancel") {
      this.cancellations.delete(t.id);
      await this.cancelRemote(t);
      return;
    }
    if (action === "recover") {
      if (t.emptyId)
        this.emptyState(t, await this.deps.api.recoverEmpty(t.emptyId));
      else if (t.sessionId) {
        const session = await this.deps.api.session(t.sessionId);
        this.sessionState(t, session);
        if (session.state === "ABANDONMENT_DECIDED")
          this.cancelState(
            t,
            await this.deps.api.recoverCancellation(t.sessionId),
          );
        else if (canRecoverCompletion(session))
          this.sessionState(t, await this.deps.api.recover(t.sessionId));
      }
      return;
    }
    if (t.emptyId) {
      if (action === "run" && t.empty?.state === "RESERVED") {
        this.emptyState(
          t,
          await this.deps.api.empty(
            { name: t.name, folderId: t.folderId, contentType: t.contentType },
            t.key,
          ),
        );
        return;
      }
      this.emptyState(t, await this.deps.api.emptyStatus(t.emptyId));
      return;
    }
    if (t.sessionId) {
      const session = await this.deps.api.session(t.sessionId);
      this.sessionState(t, session);
      if (session.state === "ABANDONMENT_DECIDED")
        this.cancelState(t, await this.deps.api.cancellation(t.sessionId));
      if (action === "check" || !["RESERVED", "OPEN"].includes(session.state))
        return;
      // A previously uncertain control dispatch cannot be inferred safe from an
      // old snapshot. Explicit recovery or operator inspection retains it.
      if (
        t.dispatch === "complete" ||
        t.dispatch === "cancel" ||
        t.dispatch === "initiate"
      ) {
        this.patch(t, {
          state: "unknown",
          message:
            "The prior control request is uncertain. Check status or recover the retained decision; it will not be sent again.",
        });
        return;
      }
    } else if (action === "check") {
      this.patch(t, {
        state: "unknown",
        message:
          "No operation ID was received. Explicit admission replay with the original key can recover its identity safely.",
      });
      return;
    }
    if (t.size === 0) {
      this.patch(t, {
        state: "preparing",
        message: "Creating a real empty file",
      });
      const empty = await this.control(t, "empty", () =>
        this.deps.api.empty(
          { name: t.name, folderId: t.folderId, contentType: t.contentType },
          t.key,
        ),
      );
      this.emptyState(t, empty);
      return;
    }
    const file = this.files.get(t.id);
    if (!file) {
      this.patch(t, {
        state: "paused",
        message: "Reselect the original file before resuming.",
      });
      return;
    }
    if (!t.sessionId) {
      this.patch(t, {
        state: "preparing",
        message: "Reserving upload capacity",
      });
      const session = await this.control(t, "admission", () =>
        this.deps.api.reserve(
          {
            name: t.name,
            size: t.size,
            folderId: t.folderId,
            contentType: t.contentType,
          },
          t.key,
        ),
      );
      this.patch(t, { sessionId: session.id, dispatch: undefined });
      this.sessionState(t, session);
      this.deps.changed();
      if (!["RESERVED", "OPEN"].includes(session.state)) return;
    }
    if (this.halted(t, signal)) return;
    let session = t.session!;
    validateSession(session, file.size);
    if (session.state === "RESERVED") {
      this.patch(t, {
        state: "preparing",
        message: "Initiating storage once",
      });
      session = await this.control(t, "initiate", () =>
        this.deps.api.initiate(t.sessionId!),
      );
      this.sessionState(t, session);
      if (session.state !== "OPEN") return;
    }
    if (this.halted(t, signal)) return;
    validateSession(session, file.size);
    if (Date.parse(session.expiresAt) <= Date.now())
      throw new TransferProblem(
        "The upload session deadline has passed. Keep its identity and inspect cancellation; expiry does not prove storage cleanup.",
      );
    const observed = await this.list(t.sessionId!, session.partCount);
    let confirmed = 0;
    this.patch(t, {
      bytes: 0,
      state: "uploading",
      message: "Verifying parts and uploading directly to storage",
    });
    for (let number = 1; number <= session.partCount; number++) {
      if (this.halted(t, signal)) return;
      const offset = (number - 1) * PART_SIZE;
      const bytes = await file
        .slice(offset, Math.min(file.size, offset + PART_SIZE))
        .arrayBuffer();
      const part = {
        partNumber: number,
        length: bytes.byteLength,
        sha256: await this.deps.hash(bytes),
      };
      if (this.halted(t, signal)) return;
      const existing = observed.find((v) => v.partNumber === number);
      if (existing) {
        matchEvidence(existing, part);
        const retained = t.evidence.find((v) => v.partNumber === number);
        if (retained && retained.etag !== existing.etag)
          throw new TransferProblem(
            "The retained opaque ETag changed. Completion is blocked pending investigation.",
          );
      } else {
        const urls = await this.deps.api.sign(t.sessionId!, part);
        if (this.halted(t, signal)) return;
        if (urls.length !== 1)
          throw new TransferProblem(
            "The signing response has an unexpected number of parts.",
          );
        const wire = await this.deps.put(
          urls[0]!,
          part,
          bytes,
          signal,
          (sent) => this.patch(t, { bytes: confirmed + sent }),
        );
        if (this.halted(t, signal)) return;
        const page = await this.deps.api.parts(t.sessionId!, number - 1, 1);
        if (page.parts.length !== 1)
          throw new TransferProblem(
            "The uploaded part has no authoritative evidence yet. Resume to inspect it.",
            "paused",
          );
        matchEvidence(page.parts[0]!, part, wire);
        observed.push(page.parts[0]!);
      }
      confirmed += bytes.byteLength;
      this.patch(t, {
        bytes: confirmed,
        evidence: [...observed].sort((a, b) => a.partNumber - b.partNumber),
      });
    }
    if (this.halted(t, signal)) return;
    this.patch(t, {
      state: "pending",
      message: "All parts verified. Waiting for backend publication.",
    });
    this.sessionState(
      t,
      await this.control(t, "complete", () =>
        this.deps.api.complete(t.sessionId!),
      ),
    );
  }
  private async control<T>(
    t: Transfer,
    dispatch: Dispatch,
    request: () => Promise<T>,
  ): Promise<T> {
    if (this.stopped)
      throw new TransferProblem(
        "The workspace closed before dispatch. Check the retained status when reopening.",
        "paused",
      );
    const previous = t.dispatch;
    this.patch(t, { dispatch });
    try {
      return await request();
    } catch (error) {
      // Only this request's known admission rejection can roll back its marker.
      // A read/recovery/replay 429 cannot erase an earlier uncertain dispatch.
      if (error instanceof ApiError && error.status === 429)
        this.patch(t, { dispatch: previous });
      throw error;
    }
  }
  private halted(t: Transfer, signal: AbortSignal) {
    return this.stopped || signal.aborted || this.cancellations.has(t.id);
  }
  private async list(id: string, count: number): Promise<StoredPart[]> {
    const parts: StoredPart[] = [];
    let marker = 0;
    for (let pageNumber = 0; pageNumber < 7; pageNumber++) {
      const page = await this.deps.api.parts(id, marker, 100);
      if (!Array.isArray(page.parts) || page.parts.length > 100)
        throw new TransferProblem("The part listing is invalid.");
      for (const part of page.parts) {
        if (
          !Number.isInteger(part.partNumber) ||
          part.partNumber <= marker ||
          part.partNumber > count
        )
          throw new TransferProblem(
            "The part listing is unordered or outside the session geometry.",
          );
        marker = part.partNumber;
        parts.push(part);
      }
      if (page.nextMarker === null) return parts;
      if (!page.parts.length || page.nextMarker !== marker)
        throw new TransferProblem("The part listing did not advance safely.");
    }
    throw new TransferProblem(
      "The part listing exceeded the bounded session geometry.",
    );
  }
  private sessionState(t: Transfer, session: UploadSession) {
    this.patch(t, { session, sessionId: session.id });
    if (session.state === "OPEN" || session.state === "RESERVED") {
      if (
        !t.dispatch ||
        t.dispatch === "admission" ||
        (t.dispatch === "initiate" && session.state === "OPEN")
      )
        this.patch(t, {
          dispatch: undefined,
          state: "paused",
          message: "Session is available. Resume with the original file.",
        });
      return;
    }
    if (session.state === "INITIATING") {
      this.patch(t, {
        state: "unknown",
        message:
          "Storage initiation is pending or unknown. Status checks cannot grant another initiation. Keep this session ID for the operator.",
      });
      return;
    }
    if (session.state === "ABANDONMENT_DECIDED") {
      this.patch(t, {
        state: "pending",
        message: "Abandonment recorded. Checking cancellation evidence.",
      });
      return;
    }
    if (session.state === "COMPLETION_DECIDED") {
      const completion = session.completion;
      if (
        !completion ||
        ![
          "PENDING",
          "UNKNOWN",
          "STORAGE_COMPLETE",
          "BLOCKED",
          "READY",
        ].includes(completion.disposition)
      ) {
        this.patch(t, {
          state: "blocked",
          message:
            "Unfamiliar completion state. Keep the session ID and check status; no new dispatch is enabled.",
        });
        return;
      }
      if (completion?.disposition === "READY") {
        this.done(t);
        return;
      }
      const state =
        completion?.disposition === "BLOCKED"
          ? "blocked"
          : completion?.disposition === "UNKNOWN"
            ? "unknown"
            : "pending";
      this.patch(t, {
        state,
        message: `${completion?.disposition ?? "Unfamiliar completion state"}${completion?.reason ? `: ${completion.reason}` : ""}. Check status or explicitly recover the retained completion. No uncertain storage completion is repeated.`,
      });
      return;
    }
    this.patch(t, {
      state: "blocked",
      message:
        "Unfamiliar session state. Keep its ID and check status; no new dispatch is enabled.",
    });
  }
  private emptyState(t: Transfer, empty: EmptyCreation) {
    this.patch(t, { emptyId: empty.id, empty });
    if (empty.state === "READY") {
      this.done(t);
      return;
    }
    const state =
      empty.state === "UNKNOWN"
        ? "unknown"
        : empty.state === "STORAGE_COMPLETE"
          ? "blocked"
          : empty.state === "FAILED" || empty.state === "REMOVED"
            ? "failed"
            : empty.state === "RESERVED"
              ? "pending"
              : "blocked";
    this.patch(t, {
      state,
      message: `${empty.state}. Empty-file recovery only inspects positive evidence and publication. There is no empty-file cancellation; removal does not prove physical reclamation.`,
    });
  }
  private cancelState(t: Transfer, cancellation: Cancellation) {
    this.patch(t, { cancellation, session: cancellation.session });
    if (cancellation.disposition === "CANCELLED") {
      this.markCancelled(t);
      return;
    }
    this.patch(t, {
      state:
        cancellation.disposition === "UNKNOWN"
          ? "unknown"
          : cancellation.disposition === "BLOCKED"
            ? "blocked"
            : cancellation.disposition === "PENDING"
              ? "pending"
              : "blocked",
      message: `${cancellation.disposition}${cancellation.reason ? `: ${cancellation.reason}` : ""}. Cancellation is not physical storage reclamation. Check status or explicitly recover cancellation.`,
    });
  }
  private async cancelRemote(t: Transfer) {
    if (t.size === 0 && (t.dispatch || t.emptyId))
      throw new TransferProblem(
        "The empty-file request was admitted and cannot be cancelled. Check or recover its status.",
      );
    if (!t.sessionId) {
      if (!t.dispatch) {
        this.markCancelled(t);
        return;
      }
      throw new TransferProblem(
        "The admission response was lost. Use the original key to recover the session identity before cancelling.",
      );
    }
    const session = await this.deps.api.session(t.sessionId);
    this.sessionState(t, session);
    if (session.state === "ABANDONMENT_DECIDED") {
      this.cancelState(t, await this.deps.api.cancellation(t.sessionId));
      return;
    }
    if (t.dispatch === "cancel")
      throw new TransferProblem(
        "The cancellation response is uncertain. Check status; do not repeat Abort.",
      );
    if (session.state === "COMPLETION_DECIDED" || t.dispatch === "complete")
      throw new TransferProblem(
        "Completion was already requested. Inspect or recover it before taking any further action.",
      );
    if (!["RESERVED", "OPEN", "INITIATING"].includes(session.state))
      throw new TransferProblem(
        "This session cannot be cancelled from its current state.",
      );
    this.patch(t, {
      state: "cancelling",
      message:
        "Requesting cancellation once; storage may retain admitted bytes.",
    });
    this.cancelState(
      t,
      await this.control(t, "cancel", () => this.deps.api.cancel(t.sessionId!)),
    );
  }
  private markCancelled(t: Transfer) {
    this.cancellations.delete(t.id);
    this.files.delete(t.id);
    this.patch(t, {
      state: "cancelled",
      hasFile: false,
      message:
        "Cancelled. This does not assert physical Garage storage reclamation.",
    });
    this.deps.changed();
  }
  private done(t: Transfer) {
    const wasDone = t.state === "completed";
    this.files.delete(t.id);
    this.clearTimer(t.id);
    this.patch(t, {
      state: "completed",
      bytes: t.size,
      hasFile: false,
      message:
        "Published READY. Current file access is checked again on download.",
    });
    if (!wasDone) this.deps.changed();
  }
  private problem(t: Transfer, error: unknown) {
    if (this.stopped) return;
    if (error instanceof TransferProblem) {
      this.patch(t, { state: error.state, message: error.message });
      return;
    }
    if (error instanceof ApiError) {
      const rejected = [400, 401, 403, 404, 409, 415, 429].includes(
        error.status,
      );
      // 429 is admission-before-business. Rejections grant no automatic retry.
      // 5xx or transport loss leave the last control dispatch uncertain.
      const delay = error.retryAfter
        ? /^\d+$/.test(error.retryAfter)
          ? Number(error.retryAfter) * 1000
          : Date.parse(error.retryAfter) - Date.now()
        : 0;
      this.patch(t, {
        state:
          error.status === 429
            ? t.dispatch
              ? "unknown"
              : "paused"
            : rejected
              ? "blocked"
              : "unknown",
        retryAt: delay > 0 ? Date.now() + Math.min(delay, 86400000) : undefined,
        authRequired: error.status === 401,
        message:
          error.status === 409
            ? "The backend rejected a conflict: the name, quota, session deadline or lifecycle may no longer permit this operation. Storage usage was refreshed. Inspect status before continuing."
            : error.status === 429 && t.dispatch
              ? `${errorMessage(error)} The earlier control outcome is still uncertain. Check status after waiting; no new dispatch permission was granted.`
              : errorMessage(error),
      });
      if (error.status === 409) this.deps.changed();
      return;
    }
    this.patch(t, {
      state: t.dispatch ? "unknown" : "paused",
      message:
        "The response was lost or unreadable. Check the connection and status. An uncertain control operation will not be automatically sent again.",
    });
  }
  private poll(t: Transfer) {
    if (
      this.stopped ||
      terminal(t) ||
      !["pending", "unknown"].includes(t.state) ||
      (!t.sessionId && !t.emptyId)
    )
      return;
    const count = this.polls.get(t.id) ?? 0;
    if (count >= 10) return;
    this.polls.set(t.id, count + 1);
    const delay = Math.max(
      Math.min(2000 * 2 ** count, 30000),
      (t.retryAt ?? 0) - Date.now(),
    );
    this.timers.set(
      t.id,
      setTimeout(() => {
        this.timers.delete(t.id);
        if (!this.stopped && !t.busy) {
          this.work.push({ id: t.id, action: "check" });
          this.pump();
        }
      }, delay),
    );
  }
}
