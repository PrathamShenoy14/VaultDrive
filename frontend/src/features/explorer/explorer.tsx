"use client";
import { useEffect, useRef, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { LayoutGrid, List, RefreshCw, Info, FolderOpen, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { getMetadata } from "./api";
import {
  canOpen,
  canRead,
  folderHref,
  type ExplorerEntry,
  type FolderResource,
} from "./contracts";
import { Breadcrumbs } from "./breadcrumbs";
import { ResourceFeed, type Selection } from "./resource-feed";
import { DetailsPanel, ReadError } from "./resource-ui";
import { SearchFilters } from "./search-filters";
import { UploadZone } from "@/features/transfers/upload-zone";
import { DownloadAction } from "@/features/transfers/download";
import { SharingAction } from "@/features/sharing/provider";
import {
  ManagementActions,
  useManagement,
} from "@/features/management/provider";

export type ExplorerMode = "drive" | "shared" | "trash" | "search";
export function Explorer({
  mode,
  folder,
  query,
}: {
  mode: ExplorerMode;
  folder?: string;
  query?: string;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const grid = params.get("view") === "grid";
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const changed = () => setRevision((value) => value + 1);
    window.addEventListener("vaultdrive:transfers-changed", changed);
    window.addEventListener("vaultdrive:management-changed", changed);
    window.addEventListener("vaultdrive:sharing-changed", changed);
    window.addEventListener("vaultdrive:session-resumed", changed);
    const visible = () => {
      if (document.visibilityState !== "hidden") changed();
    };
    window.addEventListener("focus", visible);
    document.addEventListener("visibilitychange", visible);
    return () => {
      window.removeEventListener("vaultdrive:transfers-changed", changed);
      window.removeEventListener("vaultdrive:management-changed", changed);
      window.removeEventListener("vaultdrive:sharing-changed", changed);
      window.removeEventListener("vaultdrive:session-resumed", changed);
      window.removeEventListener("focus", visible);
      document.removeEventListener("visibilitychange", visible);
    };
  }, []);
  const refresh = () => {
    setRevision((value) => value + 1);
    router.refresh();
  };
  const switchView = (view: string) => {
    const next = new URLSearchParams(params);
    next.set("view", view);
    router.push(`${pathname}?${next}`, { scroll: false });
  };
  return (
    <div>
      <div className="mb-5 flex flex-wrap items-center justify-between gap-4">
        <div>
          <p className="mb-1 text-xs font-medium tracking-wide text-muted-foreground">
            YOUR WORKSPACE
          </p>
          <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">
            {mode === "drive"
              ? "My Drive"
              : mode === "shared"
                ? "Shared with me"
                : mode === "trash"
                  ? "Trash"
                  : "Search results"}
          </h1>
        </div>
        <div className="flex items-center gap-1">
          <Button
            variant="ghost"
            size="icon"
            aria-label="Refresh resources and storage"
            onClick={refresh}
          >
            <RefreshCw aria-hidden="true" />
          </Button>
          <div
            role="group"
            aria-label="File view"
            className="flex rounded-lg border p-1"
          >
            <Button
              variant={grid ? "ghost" : "secondary"}
              size="icon-sm"
              aria-label="List view"
              aria-pressed={!grid}
              onClick={() => switchView("list")}
            >
              <List aria-hidden="true" />
            </Button>
            <Button
              variant={grid ? "secondary" : "ghost"}
              size="icon-sm"
              aria-label="Grid view"
              aria-pressed={grid}
              onClick={() => switchView("grid")}
            >
              <LayoutGrid aria-hidden="true" />
            </Button>
          </div>
        </div>
      </div>
      {mode === "search" && (
        <>
          <p className="mb-4 break-words text-sm text-muted-foreground">
            {query && new URLSearchParams(query).get("q")
              ? `Filename matches for “${new URLSearchParams(query).get("q")}”`
              : "Enter a filename in the search bar to begin."}
          </p>
          <SearchFilters />
        </>
      )}
      <div className="mb-5 flex flex-wrap items-center justify-between gap-2 border-b pb-4 text-xs text-muted-foreground">
        <label className="flex max-w-full min-w-0 flex-wrap items-center gap-2">
          Sort
          <select
            aria-label="Sort order"
            title="The backend supports this fixed order only"
            disabled
            className="max-w-full min-w-0 rounded-md border bg-background p-2 text-foreground"
            defaultValue="backend"
          >
            <option value="backend">
              {mode === "trash" ? "Deleted · newest first" : "Name · ascending"}
            </option>
          </select>
        </label>
        <span>
          Fixed order ·{" "}
          {mode === "drive" || folder
            ? "folders and files load separately"
            : mode === "trash"
              ? "separate folder and file feeds"
              : "50 per page"}
        </span>
      </div>
      {mode === "trash" && (
        <p className="rounded-xl bg-muted/50 p-4 text-sm text-muted-foreground">
          Restore available items or request permanent deletion. Pending purge
          entries have no mutation actions. Logical deletion and physical
          cleanup are separate.
        </p>
      )}
      <Scope
        key={`${mode}:${folder ?? ""}:${query ?? ""}`}
        mode={mode}
        folder={folder}
        query={query}
        grid={grid}
        refresh={refresh}
        revision={revision}
      />
    </div>
  );
}
function Scope({
  mode,
  folder,
  query,
  grid,
  refresh,
  revision,
}: {
  mode: ExplorerMode;
  folder?: string;
  query?: string;
  grid: boolean;
  refresh: () => void;
  revision: number;
}) {
  const management = useManagement();
  const [metadata, setMetadata] = useState<FolderResource>();
  const [error, setError] = useState<unknown>();
  const [selection, setSelection] = useState<Selection>({
    keys: new Set(),
    entries: new Map(),
  });
  const [details, setDetails] = useState<ExplorerEntry | null>(null);
  const toolbar = useRef<HTMLDivElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const folderLoaded = !!metadata;
  const router = useRouter();
  useEffect(() => {
    if (!folder) return;
    const controller = new AbortController();
    getMetadata("FOLDER", folder, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) {
          setMetadata(value);
          setError(undefined);
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted) setError(error);
      });
    return () => controller.abort();
  }, [folder, revision]);
  useEffect(() => {
    if (folderLoaded) heading.current?.focus();
  }, [folderLoaded]);
  if (error) return <ReadError error={error} retry={refresh} />;
  if (folder && !metadata)
    return (
      <div role="status" aria-label="Loading folder">
        <Skeleton className="h-8 w-1/2" />
        <Skeleton className="mt-6 h-40 w-full" />
      </div>
    );
  if (metadata && !metadata.allowedCapabilities.includes("LIST_CHILDREN"))
    return (
      <p role="alert">This folder cannot be opened with your current access.</p>
    );
  const selected = [...selection.keys]
    .map((key) => selection.entries.get(key))
    .filter((entry): entry is ExplorerEntry => !!entry);
  const single = selected.length === 1 ? selected[0] : undefined;
  const props = {
    grid,
    shared: mode === "shared",
    selection,
    setSelection,
    details: setDetails,
    revision,
  };
  const content = (
    <>
      {management &&
        ((mode === "drive" && !folder) ||
          metadata?.allowedCapabilities.includes("CREATE_FOLDER")) && (
          <Button
            className="mb-4"
            onClick={() =>
              management.open({ action: "create", parentId: folder ?? null })
            }
          >
            Create folder
          </Button>
        )}
      {metadata && (
        <>
          <Breadcrumbs
            folder={metadata}
            shared={mode === "shared" || metadata.effectiveRole !== "OWNER"}
          />
          <div className="mb-4 flex flex-wrap items-baseline gap-3">
            <h2
              ref={heading}
              tabIndex={-1}
              className="break-all text-xl font-medium outline-none"
            >
              {metadata.name}
            </h2>
            <span className="text-xs text-muted-foreground">
              {metadata.effectiveRole === "OWNER"
                ? "Owner"
                : metadata.effectiveRole === "EDITOR"
                  ? "Editor"
                  : "Viewer"}
            </span>
            <SharingAction
              entry={{ resourceType: "FOLDER", resource: metadata }}
            />
          </div>
        </>
      )}
      <div
        ref={toolbar}
        role="toolbar"
        aria-label="Selection actions"
        tabIndex={-1}
        className="flex min-h-12 flex-wrap items-center gap-2 rounded-xl bg-muted/40 px-3 py-2 text-sm"
      >
        <span role="status" aria-live="polite" className="mr-auto">
          {selected.length
            ? `${selected.length} selected`
            : "Select items to see actions"}
        </span>
        {single && canOpen(single) && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() =>
              router.push(
                folderHref(
                  single.resource.id,
                  mode === "shared" ||
                    (single.resourceType !== "TRASH" &&
                      single.resource.effectiveRole !== "OWNER"),
                ),
              )
            }
          >
            <FolderOpen aria-hidden="true" />
            Open
          </Button>
        )}
        <Button
          variant="ghost"
          size="sm"
          disabled={
            !single || (!canRead(single) && single.resourceType !== "TRASH")
          }
          onClick={() => {
            if (single) setDetails(single);
          }}
        >
          <Info aria-hidden="true" />
          Details
        </Button>
        {single?.resourceType === "FILE" &&
          single.resource.allowedCapabilities.includes("DOWNLOAD") && (
            <DownloadAction id={single.resource.id} />
          )}
        {single && <ManagementActions entry={single} />}
        {single && <SharingAction entry={single} />}
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label="Clear selection"
          disabled={!selected.length}
          onClick={() => {
            toolbar.current?.focus();
            setSelection({ keys: new Set(), entries: new Map() });
          }}
        >
          <X aria-hidden="true" />
        </Button>
      </div>
      <details className="mt-3 text-xs text-muted-foreground">
        <summary className="cursor-pointer">
          Selection and keyboard shortcuts
        </summary>
        <p className="mt-2 leading-relaxed">
          Use checkboxes or Ctrl/⌘ click for multiple items, Shift click for a
          range. Arrow keys move focus; Space selects; Enter opens; Shift+F10
          shows actions.
        </p>
      </details>
      {mode === "trash" ? (
        <>
          <ResourceFeed
            {...props}
            title="Trashed folders"
            feed={{ kind: "trashFolders" }}
          />
          <ResourceFeed
            {...props}
            title="Trashed files"
            feed={{ kind: "trashFiles" }}
          />
        </>
      ) : mode === "search" ? (
        query && new URLSearchParams(query).get("q") ? (
          <ResourceFeed
            {...props}
            title="Results"
            feed={{ kind: "search", query }}
          />
        ) : null
      ) : mode === "shared" && !folder ? (
        <ResourceFeed
          {...props}
          title="Shared resources"
          feed={{ kind: "shared" }}
        />
      ) : (
        <>
          <ResourceFeed
            {...props}
            title="Folders"
            feed={{ kind: "folders", folder }}
          />
          <ResourceFeed
            {...props}
            title="Files"
            feed={{ kind: "files", folder }}
          />
        </>
      )}
      <DetailsPanel
        entry={details}
        close={() => setDetails(null)}
        revision={revision}
      />
    </>
  );
  const canUpload =
    (mode === "drive" && !folder) ||
    ((mode === "drive" || mode === "shared") &&
      metadata?.allowedCapabilities.includes("UPLOAD"));
  return canUpload ? (
    <UploadZone folderId={folder ?? null}>{content}</UploadZone>
  ) : (
    content
  );
}
