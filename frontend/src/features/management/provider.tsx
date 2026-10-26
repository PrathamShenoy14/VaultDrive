"use client";
import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
} from "react";
import { useRouter } from "next/navigation";
import type { components } from "@/lib/api/generated";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Dialog,
  DialogContent,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import type { ExplorerEntry } from "@/features/explorer/contracts";
import { DestinationPicker } from "./destination-picker";
import {
  ManagementStore,
  actions,
  controls,
  journal,
  labels,
  managementError,
  nameError,
  normalizedName,
  readJournal,
  uncertain,
  type Action,
  type Operation,
} from "./model";

type Intent =
  | { action: Action; entry: ExplorerEntry }
  | { action: "create"; parentId: string | null };
const Context = createContext<{ open: (intent: Intent) => void } | null>(null);
export function useManagement() {
  return useContext(Context);
}
export function ManagementProvider({
  userId,
  children,
}: {
  userId: string;
  children: React.ReactNode;
}) {
  const router = useRouter();
  const [store, setStore] = useState<ManagementStore>();
  const [revision, setRevision] = useState(0);
  const [intent, setIntent] = useState<Intent>();
  const [notice, setNotice] = useState("");
  const returnFocus = useRef<HTMLElement | null>(null);
  useEffect(() => {
    const key = `vaultdrive-management-v1:${userId}`;
    const instance = new ManagementStore({
      changed: () => {
        router.refresh();
        window.dispatchEvent(new Event("vaultdrive:management-changed"));
      },
      persist: (records) => {
        try {
          sessionStorage.setItem(key, journal(records));
        } catch {
          /* IDs remain visible. */
        }
      },
    });
    let raw: string | null = null;
    try {
      raw = sessionStorage.getItem(key);
    } catch {
      /* In-memory evidence remains usable. */
    }
    instance.restore(readJournal(raw));
    // The effect owns this store lifetime, including StrictMode remounts.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setStore(instance);
    const pause = () => instance.stop();
    const resume = () => setRevision((value) => value + 1);
    window.addEventListener("vaultdrive:session-paused", pause);
    window.addEventListener("vaultdrive:session-resumed", resume);
    return () => {
      instance.stop();
      window.removeEventListener("vaultdrive:session-paused", pause);
      window.removeEventListener("vaultdrive:session-resumed", resume);
    };
  }, [userId, router, revision]);
  return (
    <Context.Provider
      value={
        store
          ? {
              open: (value) => {
                const active = document.activeElement as HTMLElement | null;
                returnFocus.current =
                  active?.getAttribute("role") === "menuitem" &&
                  "entry" in value
                    ? ([
                        ...document.querySelectorAll<HTMLButtonElement>(
                          "button[aria-label]",
                        ),
                      ].find(
                        (button) =>
                          button.getAttribute("aria-label") ===
                          `Actions for ${value.entry.resource.name}`,
                      ) ?? active)
                    : active;
                setNotice("");
                setIntent(value);
              },
            }
          : null
      }
    >
      {children}
      {notice && (
        <div
          role="status"
          className="fixed top-20 right-4 z-40 max-w-sm rounded-xl border bg-popover p-4 text-sm shadow-lg"
        >
          <p>{notice}</p>
          <Button variant="ghost" size="sm" onClick={() => setNotice("")}>
            Dismiss
          </Button>
        </div>
      )}
      {store && <OperationPanel store={store} />}
      <Dialog
        open={!!intent}
        onOpenChange={(open) => {
          if (!open) setIntent(undefined);
        }}
      >
        <DialogContent
          finalFocus={() => returnFocus.current}
          className="max-h-[85dvh] overflow-y-auto sm:max-w-lg"
        >
          {store && intent && (
            <MutationForm
              key={
                intent.action === "create"
                  ? `create:${intent.parentId ?? "root"}`
                  : `${intent.action}:${intent.entry.resourceType}:${intent.entry.resource.id}`
              }
              intent={intent}
              store={store}
              done={(message) => {
                setIntent(undefined);
                setNotice(message);
              }}
            />
          )}
        </DialogContent>
      </Dialog>
    </Context.Provider>
  );
}
export function ManagementActions({ entry }: { entry: ExplorerEntry }) {
  const management = useManagement();
  return (
    management &&
    actions(entry).map((action) => (
      <Button
        key={action}
        variant="ghost"
        size="sm"
        onClick={() => management.open({ action, entry })}
      >
        {labels[action]}
      </Button>
    ))
  );
}
function extension(value: string) {
  const dot = value.lastIndexOf(".");
  return dot > 0 && dot < value.length - 1 ? value.slice(dot) : "";
}
export function MutationForm({
  intent,
  store,
  done,
}: {
  intent: Intent;
  store: ManagementStore;
  done: (message: string) => void;
}) {
  const entry = "entry" in intent ? intent.entry : undefined;
  const action = intent.action;
  const type =
    entry?.resourceType === "TRASH"
      ? entry.resource.resourceType
      : entry?.resourceType;
  const [name, setName] = useState(entry?.resource.name ?? "");
  const [destination, setDestination] = useState<{
    id: string | null;
    name: string;
  }>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [blocked, setBlocked] = useState(false);
  const lock = useRef(false);
  const withName = ["create", "rename", "copy"].includes(action);
  const pickDestination = action === "move" || action === "copy";
  const title = action === "create" ? "Create folder" : labels[action];
  const originalExtension =
    entry?.resourceType === "FILE" ? extension(entry.resource.name) : "";
  const newExtension = extension(normalizedName(name));
  const validation = withName
    ? (nameError(name) ??
      (action === "rename" &&
      originalExtension &&
      newExtension &&
      originalExtension.toLowerCase() !== newExtension.toLowerCase()
        ? "Keep the existing file extension."
        : action === "rename" &&
            originalExtension &&
            !newExtension &&
            normalizedName(name).length + originalExtension.length > 255
          ? "The name including its preserved extension must be at most 255 characters."
          : undefined))
    : undefined;
  return (
    <form
      className="space-y-4"
      onSubmit={async (event) => {
        event.preventDefault();
        if (
          lock.current ||
          blocked ||
          validation ||
          (pickDestination && !destination)
        )
          return;
        lock.current = true;
        setBusy(true);
        setError(undefined);
        const sourceId =
          entry?.resource.id ??
          (intent.action === "create" ? (intent.parentId ?? "root") : "root");
        const evidence = {
          name: `${title}: ${entry?.resource.name ?? normalizedName(name)}`,
          sourceId,
          sourceType: type ?? ("FOLDER" as const),
        };
        try {
          if (action === "copy" && type) {
            await store.copy(type, sourceId, {
              name: normalizedName(name),
              destinationFolderId: destination!.id,
            });
            done(
              "Copy request recorded. Follow its authoritative state in Management operations.",
            );
            return;
          }
          let path = `${type === "FILE" ? "files" : "folders"}/${sourceId}`;
          let method = "POST";
          let body: unknown;
          if (intent.action === "create") {
            path = "folders";
            body = {
              name: normalizedName(name),
              parentFolderId: intent.parentId,
            } satisfies components["schemas"]["folder_dto_CreateFolderRequest"];
          }
          if (action === "rename") {
            if (type === "FILE") path += "/name";
            method = "PATCH";
            body = {
              name: normalizedName(name),
            } satisfies components["schemas"]["file_dto_RenameFileRequest"];
          }
          if (action === "move") {
            path += "/move";
            method = "PATCH";
            body =
              type === "FILE"
                ? ({
                    folderId: destination!.id,
                  } satisfies components["schemas"]["file_dto_MoveFileRequest"])
                : ({
                    destinationFolderId: destination!.id,
                  } satisfies components["schemas"]["folder_dto_MoveFolderRequest"]);
          }
          if (action === "trash") method = "DELETE";
          if (action === "restore" || action === "purge") {
            path = `${type === "FILE" ? "files" : "trash/folders"}/${sourceId}/${action === "purge" ? "permanent" : "restore"}`;
            method = action === "purge" ? "DELETE" : "POST";
          }
          const result = await store.mutate<{
            name?: string;
            folderId?: string | null;
            restoredName?: string;
            parentFolderId?: string | null;
          }>(path, method, body, evidence, action === "purge");
          done(
            action === "purge"
              ? "Deletion accepted. Logical progress appears in Management operations; physical cleanup is not confirmed."
              : action === "restore"
                ? `Restored as ${result.restoredName ?? result.name}. Destination: ${("restoredName" in result ? result.parentFolderId : result.folderId) ?? "My Drive"}.`
                : `${title} confirmed.`,
          );
        } catch (error) {
          setError(managementError(error));
          setBlocked(uncertain(error));
        } finally {
          lock.current = false;
          setBusy(false);
        }
      }}
    >
      <DialogTitle>{title}</DialogTitle>
      <DialogDescription>
        {entry ? entry.resource.name : "Add a folder in this location."}
      </DialogDescription>
      {withName && (
        <div className="space-y-2">
          <label htmlFor="management-name">Name</label>
          <Input
            id="management-name"
            autoFocus
            value={name}
            disabled={busy || blocked}
            aria-invalid={!!validation}
            aria-describedby={validation ? "name-error" : undefined}
            onChange={(event) => setName(event.target.value)}
          />
          {validation && (
            <p id="name-error" className="text-sm text-destructive">
              {validation}
            </p>
          )}
          {action === "rename" && type === "FILE" && (
            <p className="text-xs text-muted-foreground">
              The existing extension is preserved by the server.
            </p>
          )}
        </div>
      )}
      {pickDestination && (
        <>
          <DestinationPicker
            moving={action === "move"}
            currentParent={
              entry?.resourceType === "FILE"
                ? entry.resource.folderId
                : entry?.resourceType === "FOLDER"
                  ? entry.resource.parentFolderId
                  : undefined
            }
            excludedId={type === "FOLDER" ? entry?.resource.id : undefined}
            onChoose={(id, name) => setDestination({ id, name })}
          />
          {destination && (
            <p role="status">Selected destination: {destination.name}</p>
          )}
        </>
      )}
      {action === "copy" && (
        <p className="text-sm text-muted-foreground">
          Copies require owned source and destination and available quota.
          Folder copies allow at most 1,000 members and depth 64. Continue
          bounded steps from Management operations.
        </p>
      )}
      {action === "trash" && (
        <p>
          This item will be moved to Trash. Folder contents become inaccessible
          until restored.
        </p>
      )}
      {action === "restore" && (
        <p>
          The server chooses the restore location and name, including fallback
          when the original parent is unavailable.
        </p>
      )}
      {action === "purge" && (
        <p className="text-destructive">
          Permanently delete this item and its contents? This cannot be undone.
          Acceptance starts asynchronous deletion and does not prove physical
          storage cleanup.
        </p>
      )}
      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
      <Button
        type="submit"
        variant={action === "purge" ? "destructive" : "default"}
        disabled={
          busy || blocked || !!validation || (pickDestination && !destination)
        }
      >
        {busy ? "Submitting…" : title}
      </Button>
      {blocked && (
        <Button
          type="button"
          variant="outline"
          onClick={() =>
            window.dispatchEvent(new Event("vaultdrive:management-changed"))
          }
        >
          Refresh affected views
        </Button>
      )}
    </form>
  );
}
function OperationPanel({ store }: { store: ManagementStore }) {
  const operations = useSyncExternalStore(
    store.subscribe,
    store.snapshot,
    store.snapshot,
  );
  if (!operations.length) return null;
  return (
    <details className="fixed bottom-4 left-4 z-40 max-h-[60dvh] w-[calc(100%-2rem)] max-w-md overflow-y-auto rounded-xl border bg-popover p-3 text-sm text-popover-foreground shadow-lg">
      <summary className="cursor-pointer font-medium">
        Management operations ({operations.length})
      </summary>
      <p className="my-3 text-xs text-muted-foreground">
        Status polling is bounded (2–30 seconds, ten reads). Continue and
        recover explicitly. Keep IDs and keys: no cross-device operation
        discovery exists. Logical completion never proves physical reclamation.
      </p>
      <ul className="space-y-4">
        {operations.map((op) => (
          <OperationRow key={op.localId} op={op} store={store} />
        ))}
      </ul>
    </details>
  );
}
function OperationRow({
  op,
  store,
}: {
  op: Operation;
  store: ManagementStore;
}) {
  const [confirm, setConfirm] = useState<"cancel" | "discard">();
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const remaining = (op.retryAt ?? 0) - Date.now();
    if (remaining <= 0) return;
    const timer = setTimeout(() => setNow(Date.now()), remaining);
    return () => clearTimeout(timer);
  }, [op.retryAt]);
  const waiting = now < (op.retryAt ?? 0);
  return (
    <li className="space-y-2 border-t pt-3">
      <p className="break-words font-medium">{op.name}</p>
      <p role="status">
        {op.status?.state ??
          (op.busy
            ? "Request pending"
            : op.rejected
              ? "Request rejected"
              : op.kind === "deletion" && op.id
                ? "Accepted; awaiting status"
                : op.id
                  ? "Status not checked"
                  : "Outcome uncertain")}
        {op.uncertain ? " · retained uncertain dispatch" : ""}
      </p>
      <p className="break-all text-xs">
        Resource: {op.sourceId}
        {op.id && <> · Operation: {op.id}</>}
        {op.key && <> · Key: {op.key}</>}
      </p>
      {op.request && (
        <p className="break-all text-xs">
          Copy name: {op.request.name} · Destination:{" "}
          {op.request.destinationFolderId ?? "My Drive"}
        </p>
      )}
      {op.mutation && (
        <details className="text-xs">
          <summary>Retained request evidence</summary>
          <p className="break-all">
            {op.mutation.method} {op.mutation.path}
          </p>
          <pre className="whitespace-pre-wrap break-all">
            {JSON.stringify(op.mutation.body)}
          </pre>
          <p>
            When no operation ID was received, status discovery may require
            operator assistance. Do not repeat permanent deletion to obtain an
            ID.
          </p>
        </details>
      )}
      {op.status && "checkpoint" in op.status && (
        <p>
          {op.status.checkpoint} / {op.status.totalMembers} members;{" "}
          {String(op.status.completedFiles)} / {String(op.status.totalFiles)}{" "}
          files. Destination stays hidden until COMPLETED.
        </p>
      )}
      {op.status && "reasonCode" in op.status && op.status.reasonCode && (
        <p>
          Reason: {op.status.reasonCode}. Operator attention may be required.
        </p>
      )}
      {op.status?.state === "LOGICALLY_COMPLETED" && (
        <p>
          Logical deletion completed. Retained objects or uncertain effects may
          remain.
        </p>
      )}
      {op.status &&
        ["UNKNOWN", "BLOCKED", "FAILED"].includes(op.status.state) && (
          <p>
            Keep this evidence. Correct eligibility or quota where applicable;
            recovery may require operator attention.
          </p>
        )}
      {op.error && (
        <p role="alert" className="text-destructive">
          {op.error}
        </p>
      )}
      {waiting && (
        <p role="status">
          Wait until {new Date(op.retryAt!).toLocaleTimeString()} before another
          request.
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        {op.id && (
          <Button
            size="sm"
            variant="outline"
            disabled={op.busy || waiting}
            onClick={() => void store.check(op)}
          >
            Check status
          </Button>
        )}
        {!op.id && op.key && (
          <Button
            size="sm"
            variant="outline"
            disabled={op.busy || waiting}
            onClick={() => void store.replay(op)}
          >
            Replay original admission
          </Button>
        )}
        {controls(op).map((action) => (
          <Button
            key={action}
            size="sm"
            variant="outline"
            disabled={op.busy || waiting}
            onClick={() =>
              action === "cancel" || action === "discard"
                ? setConfirm(action)
                : void store.control(op, action)
            }
          >
            {action === "execute"
              ? "Continue copy (bounded step)"
              : action === "recover"
                ? "Recover existing evidence"
                : action === "cancel"
                  ? "Cancel copy"
                  : "Discard partial copy"}
          </Button>
        ))}
      </div>
      {confirm && (
        <div
          role="group"
          aria-label="Confirm copy action"
          className="space-y-2 rounded border p-2"
        >
          <p>
            {confirm === "cancel"
              ? "Close unpublished copy? Partial charges and retained objects remain until eligible disposal."
              : "Permanently discard this partial copy through asynchronous deletion? Physical cleanup is not confirmed."}
          </p>
          <Button
            variant="destructive"
            size="sm"
            disabled={op.busy}
            onClick={() => {
              void store.control(op, confirm);
              setConfirm(undefined);
            }}
          >
            Confirm {confirm}
          </Button>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => setConfirm(undefined)}
          >
            Keep evidence
          </Button>
        </div>
      )}
    </li>
  );
}
