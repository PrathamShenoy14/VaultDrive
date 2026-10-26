import type { components } from "@/lib/api/generated";
import type { ExplorerEntry } from "@/features/explorer/contracts";
import { ApiError, errorMessage } from "@/lib/api/transport";
import { sessionRequest } from "@/features/auth/session-request";

export type Action = "rename" | "move" | "copy" | "trash" | "restore" | "purge";
export const labels: Record<Action, string> = {
  rename: "Rename",
  move: "Move",
  copy: "Copy",
  trash: "Move to Trash",
  restore: "Restore",
  purge: "Delete permanently",
};
export function actions(entry: ExplorerEntry): Action[] {
  const capabilities = entry.resource.allowedCapabilities;
  return (
    entry.resourceType === "TRASH"
      ? ["restore", "purge"]
      : (["rename", "move", "copy", "trash"] as Action[])
  ).filter((action) => capabilities.includes(action.toUpperCase())) as Action[];
}
// Java String.trim and UTF-16 length; controls are rejected BEFORE trimming.
export function normalizedName(name: string) {
  return name.replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, "");
}
export function nameError(name: string) {
  if (
    /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]*$/.test(
      name,
    )
  )
    return "Enter a name.";
  if (/[\/\\\x00-\x1f\x7f-\x9f]/.test(name))
    return "Names cannot contain path separators or control characters.";
  if (normalizedName(name).length > 255)
    return "Names must be at most 255 characters.";
}
export function uncertain(error: unknown) {
  return (
    !(error instanceof ApiError) || error.status >= 500 || error.status === 408
  );
}
export function managementError(error: unknown) {
  if (uncertain(error))
    return "The outcome is uncertain. Check affected views and retained operation evidence before taking another action. This request was not automatically retried.";
  if (error instanceof ApiError) {
    if (error.status === 400)
      return "Invalid name or destination. File renames must preserve the existing extension.";
    if (error.status === 403)
      return "Your current permissions do not allow this action.";
    if (error.status === 404)
      return "The resource or destination is missing or no longer accessible.";
    if (error.status === 409)
      return "A name collision, folder cycle, quota limit, copy size/depth limit, or lifecycle conflict prevents this action. Refresh and check the destination.";
  }
  return errorMessage(error);
}
export type FileCopy =
  components["schemas"]["file_FileCopyTransactions_Status"];
export type FolderCopy =
  components["schemas"]["folder_FolderCopyTransactions_Status"];
export type Deletion =
  components["schemas"]["deletion_DeletionOperationService_Status"];
export type CopyRequest =
  components["schemas"]["file_FileCopyTransactions_Request"];
export type Operation = {
  localId: string;
  kind: "file-copy" | "folder-copy" | "deletion" | "uncertain";
  name: string;
  sourceId: string;
  sourceType: "FILE" | "FOLDER";
  id?: string;
  key?: string;
  request?: CopyRequest;
  status?: FileCopy | FolderCopy | Deletion;
  error?: string;
  busy?: boolean;
  uncertain?: boolean;
  rejected?: boolean;
  retryAt?: number;
  mutation?: { path: string; method: string; body?: unknown };
};
export async function managementRequest<T>(
  path: string,
  method = "GET",
  body?: unknown,
  key?: string,
): Promise<T> {
  return sessionRequest<T>(`/api/v1/${path}`, {
    method,
    credentials: "same-origin",
    cache: "no-store",
    signal: AbortSignal.timeout(30000),
    headers: {
      ...(method !== "GET" ? { "X-VaultDrive-Client": "1" } : {}),
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(key ? { "Idempotency-Key": key } : {}),
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  });
}
export function operationPath(op: Operation) {
  return `${op.kind === "deletion" ? "deletion-operations" : op.kind === "folder-copy" ? "folder-copy-operations" : "copy-operations"}/${op.id}`;
}
export function terminal(op: Operation) {
  return [
    "READY",
    "COMPLETED",
    "CANCELLED",
    "FAILED",
    "LOGICALLY_COMPLETED",
  ].includes(op.status?.state ?? "");
}
export function controls(
  op: Operation,
): ("execute" | "recover" | "cancel" | "discard")[] {
  if (!op.id || !op.status || op.kind === "deletion" || op.kind === "uncertain")
    return [];
  const state = op.status.state;
  if (op.kind === "folder-copy" && ["FAILED", "CANCELLED"].includes(state))
    return "disposalOperationId" in op.status && op.status.disposalOperationId
      ? []
      : ["discard"];
  if (terminal(op)) return [];
  if (
    !["ACTIVE", "RESERVED", "BLOCKED", "STORAGE_COMPLETE", "UNKNOWN"].includes(
      state,
    )
  )
    return [];
  return [
    ...(!op.uncertain &&
    ((op.kind === "folder-copy" && ["ACTIVE", "BLOCKED"].includes(state)) ||
      (op.kind === "file-copy" && state === "RESERVED"))
      ? ["execute" as const]
      : []),
    "recover",
    "cancel",
  ];
}

type Dependencies = {
  request?: typeof managementRequest;
  changed: () => void;
  persist: (ops: Operation[]) => void;
};
export class ManagementStore {
  private records: Operation[] = [];
  private listeners = new Set<() => void>();
  private timers = new Map<string, ReturnType<typeof setTimeout>>();
  private stopped = false;
  private request: typeof managementRequest;
  constructor(private deps: Dependencies) {
    this.request = deps.request ?? managementRequest;
  }
  snapshot = () => this.records;
  subscribe = (fn: () => void) => {
    this.listeners.add(fn);
    return () => {
      this.listeners.delete(fn);
    };
  };
  private save() {
    this.records = [...this.records];
    this.deps.persist(this.records);
    this.listeners.forEach((fn) => fn());
  }
  restore(records: Operation[]) {
    this.records = records.map((op) => ({
      ...op,
      busy: false,
      status: undefined,
      uncertain: op.kind === "deletion" || op.rejected ? false : true,
      error:
        "Restored evidence. Check status or replay the original copy admission; no mutation ran on reload.",
    }));
    this.save();
  }
  add(op: Operation) {
    this.records.push(op);
    this.save();
  }
  private update(op: Operation, value: Partial<Operation>) {
    Object.assign(op, value);
    this.save();
  }
  stop() {
    this.stopped = true;
    this.timers.forEach(clearTimeout);
    this.timers.clear();
  }
  private failure(op: Operation, error: unknown, mutation = true) {
    const seconds = error instanceof ApiError ? Number(error.retryAfter) : 0;
    this.update(op, {
      error: managementError(error),
      uncertain: op.uncertain || (mutation && uncertain(error)),
      retryAt: seconds > 0 ? Date.now() + seconds * 1000 : undefined,
    });
    if (error instanceof ApiError && error.status === 409) this.deps.changed();
  }
  async mutate<T>(
    path: string,
    method: string,
    body: unknown,
    evidence: Pick<Operation, "name" | "sourceId" | "sourceType">,
    deletion = false,
  ): Promise<T> {
    if (this.stopped) throw new DOMException("Workspace stopped", "AbortError");
    // Retain intent before dispatch, including permanent deletion with a lost acceptance.
    const op: Operation = {
      ...evidence,
      localId: crypto.randomUUID(),
      kind: "uncertain",
      busy: true,
      uncertain: true,
      mutation: { path, method, body },
    };
    this.add(op);
    try {
      const value = await this.request<T>(path, method, body);
      if (deletion) {
        const accepted =
          value as components["schemas"]["deletion_DeletionOperationController_Accepted"];
        this.update(op, {
          kind: "deletion",
          id: accepted.operationId,
          uncertain: false,
        });
        this.poll(op);
      } else this.records = this.records.filter((item) => item !== op);
      this.deps.changed();
      return value;
    } catch (error) {
      if (uncertain(error)) this.failure(op, error);
      else {
        this.records = this.records.filter((item) => item !== op);
        if (error instanceof ApiError && error.status === 409)
          this.deps.changed();
      }
      throw error;
    } finally {
      op.busy = false;
      this.save();
    }
  }
  async copy(
    sourceType: "FILE" | "FOLDER",
    sourceId: string,
    request: CopyRequest,
  ) {
    if (this.stopped) throw new DOMException("Workspace stopped", "AbortError");
    const op: Operation = {
      localId: crypto.randomUUID(),
      kind: sourceType === "FILE" ? "file-copy" : "folder-copy",
      sourceType,
      sourceId,
      name: request.name,
      request,
      key: crypto.randomUUID(),
    };
    this.add(op);
    await this.replay(op);
    return op;
  }
  async replay(op: Operation) {
    if (
      this.stopped ||
      op.busy ||
      op.id ||
      !op.request ||
      !op.key ||
      Date.now() < (op.retryAt ?? 0)
    )
      return;
    this.update(op, { busy: true, error: undefined });
    try {
      const status = await this.request<FileCopy | FolderCopy>(
        `${op.sourceType === "FILE" ? "files" : "folders"}/${op.sourceId}/copy`,
        "POST",
        op.request,
        op.key,
      );
      this.update(op, {
        id: status.id,
        status,
        uncertain: false,
        rejected: false,
      });
      this.deps.changed();
      this.poll(op);
    } catch (error) {
      this.update(op, { rejected: !op.uncertain && !uncertain(error) });
      this.failure(op, error);
    } finally {
      this.update(op, { busy: false });
    }
  }
  async check(op: Operation, pollCount = 0) {
    if (this.stopped || op.busy || !op.id || Date.now() < (op.retryAt ?? 0))
      return;
    const timer = this.timers.get(op.localId);
    if (timer) clearTimeout(timer);
    this.update(op, { busy: true, error: undefined });
    try {
      const status = await this.request<FileCopy | FolderCopy | Deletion>(
        operationPath(op),
      );
      this.update(op, {
        status,
        ...(terminal({ ...op, status }) ? { uncertain: false } : {}),
      });
      this.trackDisposal(op);
      // GET does not erase a lost control dispatch. Recover remains the safe path.
      if (terminal(op)) this.deps.changed();
      if (pollCount < 9) this.poll(op, pollCount + 1);
    } catch (error) {
      this.failure(op, error, false);
    } finally {
      this.update(op, { busy: false });
    }
  }
  private poll(op: Operation, count = 0) {
    const old = this.timers.get(op.localId);
    if (old) clearTimeout(old);
    if (
      this.stopped ||
      !op.id ||
      terminal(op) ||
      op.status?.state === "BLOCKED"
    )
      return;
    this.timers.set(
      op.localId,
      setTimeout(
        () => {
          this.timers.delete(op.localId);
          void this.check(op, count);
        },
        Math.max(
          Math.min(2000 * 2 ** count, 30000),
          (op.retryAt ?? 0) - Date.now(),
        ),
      ),
    );
  }
  async control(op: Operation, action: ReturnType<typeof controls>[number]) {
    if (
      this.stopped ||
      op.busy ||
      !controls(op).includes(action) ||
      Date.now() < (op.retryAt ?? 0)
    )
      return;
    const timer = this.timers.get(op.localId);
    if (timer) clearTimeout(timer);
    const previouslyUncertain = op.uncertain;
    this.update(op, { busy: true, error: undefined, uncertain: true });
    try {
      const status = await this.request<FileCopy | FolderCopy>(
        `${operationPath(op)}/${action}`,
        "POST",
      );
      this.update(op, { status, uncertain: false });
      this.trackDisposal(op);
      this.deps.changed();
      this.poll(op);
    } catch (error) {
      if (!uncertain(error))
        this.update(op, { uncertain: previouslyUncertain });
      this.failure(op, error);
    } finally {
      this.update(op, { busy: false });
    }
  }
  private trackDisposal(op: Operation) {
    const status = op.status;
    if (
      status &&
      "disposalOperationId" in status &&
      status.disposalOperationId &&
      !this.records.some((item) => item.id === status.disposalOperationId)
    ) {
      const disposal: Operation = {
        localId: crypto.randomUUID(),
        kind: "deletion",
        name: op.name,
        sourceType: "FOLDER",
        sourceId: status.folderId ?? op.sourceId,
        id: status.disposalOperationId,
      };
      this.add(disposal);
      this.poll(disposal);
    }
  }
}

export function journal(records: Operation[]) {
  return JSON.stringify(
    records.map(
      ({
        localId,
        kind,
        name,
        sourceId,
        sourceType,
        id,
        key,
        request,
        mutation,
        rejected,
      }) => ({
        localId,
        kind,
        name,
        sourceId,
        sourceType,
        id,
        key,
        request,
        mutation,
        rejected,
      }),
    ),
  );
}
export function readJournal(raw: string | null): Operation[] {
  try {
    const parsed: unknown = JSON.parse(raw ?? "[]");
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (op): op is Operation =>
        !!op &&
        typeof op === "object" &&
        typeof op.localId === "string" &&
        typeof op.name === "string" &&
        typeof op.sourceId === "string" &&
        ["FILE", "FOLDER"].includes(op.sourceType) &&
        ["file-copy", "folder-copy", "deletion", "uncertain"].includes(
          op.kind,
        ) &&
        (!op.id || typeof op.id === "string") &&
        (!op.request ||
          (typeof op.request.name === "string" &&
            (!op.request.destinationFolderId ||
              typeof op.request.destinationFolderId === "string"))),
    );
  } catch {
    return [];
  }
}
