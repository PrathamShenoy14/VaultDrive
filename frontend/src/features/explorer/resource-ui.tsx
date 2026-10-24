"use client";
import { useEffect, useRef, useState } from "react";
import {
  File,
  FileText,
  FileImage,
  FileAudio,
  FileVideo,
  FileArchive,
  Folder,
  MoreHorizontal,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from "@/components/ui/dropdown-menu";
import {
  Sheet,
  SheetContent,
  SheetTitle,
  SheetDescription,
} from "@/components/ui/sheet";
import { Skeleton } from "@/components/ui/skeleton";
import { errorMessage, ApiError } from "@/lib/api/transport";
import { formatBytes } from "@/features/shell/storage";
import { getMetadata } from "./api";
import { downloadHref } from "@/features/transfers/download";
import { useManagement } from "@/features/management/provider";
import { useSharing } from "@/features/sharing/provider";
import { actions, labels } from "@/features/management/model";
import {
  canOpen,
  canRead,
  type ExplorerEntry,
  type Resource,
} from "./contracts";

export function ResourceIcon({
  entry,
  large = false,
}: {
  entry: ExplorerEntry;
  large?: boolean;
}) {
  const folder =
    entry.resourceType === "FOLDER" ||
    (entry.resourceType === "TRASH" &&
      entry.resource.resourceType === "FOLDER");
  const mime =
    entry.resourceType === "FILE" ? (entry.resource.contentType ?? "") : "";
  const Icon = folder
    ? Folder
    : mime.startsWith("image/")
      ? FileImage
      : mime.startsWith("audio/")
        ? FileAudio
        : mime.startsWith("video/")
          ? FileVideo
          : /zip|tar|compressed/.test(mime)
            ? FileArchive
            : /text|pdf|document/.test(mime)
              ? FileText
              : File;
  return (
    <Icon
      aria-hidden="true"
      strokeWidth={1.6}
      className={`${large ? "size-14" : "size-5"} shrink-0 ${folder ? "fill-blue-100 text-blue-600 dark:fill-blue-950 dark:text-blue-300" : "text-muted-foreground"}`}
    />
  );
}
export function dateLabel(value: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium" }).format(
    new Date(value),
  );
}
export function entryType(entry: ExplorerEntry) {
  return entry.resourceType === "FILE"
    ? (entry.resource.contentType ?? "File")
    : entry.resourceType === "FOLDER"
      ? "Folder"
      : `${entry.resource.resourceType === "FOLDER" ? "Folder" : "File"} · ${entry.resource.lifecycleState}`;
}
export function entryDate(entry: ExplorerEntry) {
  return entry.resourceType === "TRASH"
    ? entry.resource.deletedAt
    : entry.resource.updatedAt;
}
export function EntryMenu({
  entry,
  open,
  setOpen,
  onOpen,
  onDetails,
}: {
  entry: ExplorerEntry;
  open: boolean;
  setOpen: (open: boolean) => void;
  onOpen: () => void;
  onDetails: () => void;
}) {
  const management = useManagement();
  const sharing = useSharing();
  return (
    <DropdownMenu open={open} onOpenChange={setOpen}>
      <DropdownMenuTrigger
        render={
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`Actions for ${entry.resource.name}`}
          />
        }
      >
        <MoreHorizontal aria-hidden="true" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-56">
        {canOpen(entry) && (
          <DropdownMenuItem onClick={onOpen}>Open folder</DropdownMenuItem>
        )}
        <DropdownMenuItem
          disabled={entry.resourceType !== "TRASH" && !canRead(entry)}
          onClick={onDetails}
        >
          View details
        </DropdownMenuItem>
        {management &&
          actions(entry).map((action) => (
            <DropdownMenuItem
              key={action}
              onClick={() => {
                setOpen(false);
                management.open({ action, entry });
              }}
            >
              {labels[action]}
            </DropdownMenuItem>
          ))}
        {entry.resourceType !== "TRASH" && (
          <>
            {entry.resourceType === "FILE" &&
              entry.resource.allowedCapabilities.includes("DOWNLOAD") && (
                <DropdownMenuItem
                  render={
                    <a
                      href={downloadHref(entry.resource.id)}
                      target="_blank"
                      rel="noopener noreferrer"
                    />
                  }
                >
                  Download
                </DropdownMenuItem>
              )}
            {sharing && canRead(entry) && (
              <DropdownMenuItem
                onClick={() => {
                  setOpen(false);
                  sharing.open(entry);
                }}
              >
                {entry.resource.allowedCapabilities.includes("MANAGE_GRANTS")
                  ? "Share"
                  : "Access details"}
              </DropdownMenuItem>
            )}
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
export function ReadError({
  error,
  retry,
}: {
  error: unknown;
  retry: () => void;
}) {
  const timeout =
    (typeof error === "object" &&
      error !== null &&
      "name" in error &&
      ["TimeoutError", "AbortError"].includes(String(error.name))) ||
    (error instanceof ApiError && error.body.error === "UPSTREAM_TIMEOUT");
  return (
    <div
      role="alert"
      className="my-4 space-y-3 rounded-xl border border-destructive/30 bg-destructive/5 p-5 text-sm"
    >
      <p>
        {timeout
          ? "This request timed out. Try again or narrow your search."
          : errorMessage(error)}
      </p>
      <div className="flex flex-wrap gap-3">
        <Button size="sm" variant="outline" onClick={retry}>
          Try again
        </Button>
        {error instanceof ApiError && error.status === 401 && (
          <a href="/login" className="self-center underline">
            Sign in
          </a>
        )}
        <a href="/drive" className="self-center underline">
          Go to My Drive
        </a>
      </div>
    </div>
  );
}
function Metadata({ entry }: { entry: ExplorerEntry }) {
  const [result, setResult] = useState<Resource>();
  const [error, setError] = useState<unknown>();
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (entry.resourceType === "TRASH") return;
    const controller = new AbortController();
    getMetadata(entry.resourceType, entry.resource.id, controller.signal)
      .then((resource) => {
        if (!controller.signal.aborted)
          setResult({ resourceType: entry.resourceType, resource } as Resource);
      })
      .catch((error) => {
        if (!controller.signal.aborted) setError(error);
      });
    return () => controller.abort();
  }, [entry, attempt]);
  if (error)
    return (
      <ReadError
        error={error}
        retry={() => {
          setError(undefined);
          setResult(undefined);
          setAttempt((value) => value + 1);
        }}
      />
    );
  const current = entry.resourceType === "TRASH" ? entry : result;
  if (!current)
    return (
      <div role="status" aria-label="Loading metadata" className="space-y-4">
        <Skeleton className="h-16 w-16" />
        <Skeleton className="h-5 w-full" />
        <Skeleton className="h-5 w-3/4" />
      </div>
    );
  const resource = current.resource;
  const fields =
    current.resourceType === "TRASH"
      ? [
          ["Type", entryType(current)],
          ["Deleted", dateLabel(current.resource.deletedAt)],
          ["Lifecycle", current.resource.lifecycleState],
          [
            "Deletion requested",
            current.resource.purgeRequestedAt
              ? dateLabel(current.resource.purgeRequestedAt)
              : "No",
          ],
        ]
      : [
          ["Type", entryType(current)],
          ["Access", current.resource.effectiveRole],
          [
            "Size",
            current.resourceType === "FILE"
              ? `${formatBytes(BigInt(current.resource.sizeBytes))} (${current.resource.sizeBytes.toString()} bytes)`
              : "—",
          ],
          ["Created", dateLabel(current.resource.createdAt)],
          ["Modified", dateLabel(current.resource.updatedAt)],
        ];
  return (
    <>
      <div className="flex justify-center rounded-xl bg-muted/40 py-8">
        <ResourceIcon entry={current} large />
      </div>
      <p className="break-words text-lg font-medium">{resource.name}</p>
      <dl className="space-y-4">
        {fields.map(([label, value]) => (
          <div key={label}>
            <dt className="text-xs text-muted-foreground">{label}</dt>
            <dd className="mt-1 break-words">{value}</dd>
          </div>
        ))}
      </dl>
      <p className="text-xs text-muted-foreground">
        {current.resourceType === "TRASH"
          ? "Trash entries cannot be opened. Available restore and deletion actions depend on current lifecycle state."
          : "Metadata reflects current backend access. Mutation permissions are rechecked by the server."}
      </p>
    </>
  );
}
export function DetailsPanel({
  entry,
  close,
  revision = 0,
}: {
  entry: ExplorerEntry | null;
  close: () => void;
  revision?: number;
}) {
  const returnFocus = useRef<HTMLElement | null>(null);
  useEffect(() => {
    if (entry) returnFocus.current = document.activeElement as HTMLElement;
  }, [entry]);
  return (
    <Sheet
      open={!!entry}
      onOpenChange={(open) => {
        if (!open) close();
      }}
    >
      <SheetContent
        className="w-full overflow-y-auto p-6 sm:max-w-sm"
        finalFocus={() => returnFocus.current}
      >
        <SheetTitle>Resource details</SheetTitle>
        <SheetDescription>
          Current metadata and access information.
        </SheetDescription>
        {entry && (
          <Metadata
            key={`${entry.resourceType}:${entry.resource.id}:${revision}`}
            entry={entry}
          />
        )}
      </SheetContent>
    </Sheet>
  );
}
