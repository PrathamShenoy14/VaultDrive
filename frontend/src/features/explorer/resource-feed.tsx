"use client";
import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { formatBytes } from "@/features/shell/storage";
import { cn } from "@/lib/utils";
import { getPage, type Feed } from "./api";
import {
  canOpen,
  canRead,
  entryKey,
  folderHref,
  type ExplorerEntry,
  type Page,
} from "./contracts";
import {
  dateLabel,
  entryDate,
  entryType,
  EntryMenu,
  ReadError,
  ResourceIcon,
} from "./resource-ui";
import { selectEntry } from "./selection";

export type Selection = {
  keys: Set<string>;
  entries: Map<string, ExplorerEntry>;
};
export function ResourceFeed({
  feed,
  title,
  grid,
  shared,
  selection,
  setSelection,
  details,
}: {
  feed: Feed;
  title: string;
  grid: boolean;
  shared: boolean;
  selection: Selection;
  setSelection: React.Dispatch<React.SetStateAction<Selection>>;
  details: (entry: ExplorerEntry) => void;
}) {
  const router = useRouter();
  const [page, setPage] = useState<Page<ExplorerEntry>>();
  const [entries, setEntries] = useState<ExplorerEntry[]>([]);
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const [menu, setMenu] = useState<string | null>(null);
  const anchor = useRef<string | null>(null);
  const active = useRef<AbortController | null>(null);
  const busy = useRef(false);
  const rows = useRef<HTMLDivElement>(null);
  async function load(next: number) {
    if (busy.current) return;
    busy.current = true;
    const controller = new AbortController();
    active.current = controller;
    try {
      const value = await getPage(feed, next, controller.signal);
      if (!controller.signal.aborted) {
        setPage(value);
        setEntries((previous) => [
          ...new Map(
            [...previous, ...value.content].map((entry) => [
              entryKey(entry),
              entry,
            ]),
          ).values(),
        ]);
      }
    } catch (error) {
      if (!controller.signal.aborted) {
        setError(error);
        // Do not leave previously loaded personalized data after a denial.
        if (
          error instanceof Error &&
          "status" in error &&
          [401, 403, 404].includes(Number(error.status))
        ) {
          setEntries([]);
          setPage(undefined);
          setSelection({ keys: new Set(), entries: new Map() });
        }
      }
    } finally {
      if (!controller.signal.aborted) setLoading(false);
      if (active.current === controller) busy.current = false;
    }
  }
  useEffect(() => {
    // Initial state is already loading. All load() setters follow awaited network I/O.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load(0);
    return () => {
      active.current?.abort();
      busy.current = false;
    };
    // Feed identity is stable for this mounted scope. Navigation/refresh remounts it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  function requestPage(next: number) {
    if (busy.current) return;
    setLoading(true);
    setError(undefined);
    void load(next);
  }
  const select = (entry: ExplorerEntry, toggle = false, range = false) => {
    const key = entryKey(entry);
    setSelection((previous) => ({
      keys: selectEntry(
        previous.keys,
        key,
        entries.map(entryKey),
        anchor.current,
        toggle,
        range,
      ),
      entries: new Map([
        ...previous.entries,
        ...entries.map((item) => [entryKey(item), item] as const),
      ]),
    }));
    if (!range) anchor.current = key;
  };
  const open = (entry: ExplorerEntry) => {
    if (canOpen(entry))
      router.push(
        folderHref(
          entry.resource.id,
          shared ||
            (entry.resourceType !== "TRASH" &&
              entry.resource.effectiveRole !== "OWNER"),
        ),
      );
    else if (canRead(entry) || entry.resourceType === "TRASH") details(entry);
  };
  function keyboard(
    event: KeyboardEvent<HTMLDivElement>,
    entry: ExplorerEntry,
    index: number,
  ) {
    if (event.target !== event.currentTarget) return;
    if (event.key === "Enter") {
      event.preventDefault();
      open(entry);
    } else if (event.key === " ") {
      event.preventDefault();
      select(entry, true, event.shiftKey);
    } else if (
      (event.shiftKey && event.key === "F10") ||
      event.key === "ContextMenu"
    ) {
      event.preventDefault();
      setMenu(entryKey(entry));
    } else if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
      event.preventDefault();
      const next =
        event.key === "Home"
          ? 0
          : event.key === "End"
            ? entries.length - 1
            : Math.max(
                0,
                Math.min(
                  entries.length - 1,
                  index + (event.key === "ArrowDown" ? 1 : -1),
                ),
              );
      rows.current
        ?.querySelectorAll<HTMLElement>("[data-resource-row]")
        [next]?.focus();
      if (event.shiftKey) select(entries[next]!, false, true);
    }
  }
  return (
    <section aria-label={title} className="mt-6 min-w-0">
      <div className="mb-3 flex flex-wrap items-center gap-3">
        <h2 className="text-sm font-semibold">{title}</h2>
        <span className="text-xs text-muted-foreground" role="status">
          {page
            ? `${entries.length} of ${page.totalElements.toString()} loaded`
            : loading
              ? "Loading…"
              : ""}
        </span>
      </div>
      {!grid && entries.length > 0 && (
        <div
          aria-hidden="true"
          className="hidden grid-cols-[2rem_minmax(0,1fr)_9rem_7rem_4rem_2rem] gap-3 border-b px-3 pb-3 text-xs text-muted-foreground lg:grid"
        >
          <span />
          <span>{feed.kind.startsWith("trash") ? "Name" : "Name ↑"}</span>
          <span>Type</span>
          <span>
            {feed.kind.startsWith("trash") ? "Deleted ↓" : "Modified"}
          </span>
          <span>Size</span>
          <span />
        </div>
      )}
      <div
        ref={rows}
        role="group"
        aria-label={`${title} resources`}
        className={
          grid
            ? "grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4"
            : "divide-y"
        }
        onKeyDown={(event) => {
          if (
            (event.ctrlKey || event.metaKey) &&
            event.key.toLowerCase() === "a"
          ) {
            event.preventDefault();
            setSelection((previous) => ({
              keys: new Set([...previous.keys, ...entries.map(entryKey)]),
              entries: new Map([
                ...previous.entries,
                ...entries.map((entry) => [entryKey(entry), entry] as const),
              ]),
            }));
          }
          if (event.key === "Escape")
            setSelection({ keys: new Set(), entries: new Map() });
        }}
      >
        {entries.map((entry, index) => {
          const key = entryKey(entry),
            selected = selection.keys.has(key);
          return (
            <div
              key={key}
              data-resource-row
              tabIndex={0}
              aria-label={`${entry.resource.name}, ${entryType(entry)}${selected ? ", selected" : ""}`}
              className={cn(
                "group min-w-0 rounded-lg p-3 outline-none transition-colors focus-visible:ring-2 focus-visible:ring-ring",
                grid
                  ? "border"
                  : "grid grid-cols-[2rem_minmax(0,1fr)_2rem] items-center gap-3 lg:grid-cols-[2rem_minmax(0,1fr)_9rem_7rem_4rem_2rem]",
                selected ? "bg-accent/70" : "hover:bg-muted/60",
              )}
              onClick={(event) => {
                if (!(event.target as HTMLElement).closest("button,input,a"))
                  select(entry, event.ctrlKey || event.metaKey, event.shiftKey);
              }}
              onDoubleClick={(event) => {
                if (!(event.target as HTMLElement).closest("button,input,a"))
                  open(entry);
              }}
              onKeyDown={(event) => keyboard(event, entry, index)}
              onContextMenu={(event) => {
                event.preventDefault();
                setMenu(key);
              }}
            >
              <div
                className={
                  grid ? "flex items-center justify-between gap-2" : "contents"
                }
              >
                <input
                  type="checkbox"
                  aria-label={`Select ${entry.resource.name}`}
                  checked={selected}
                  onChange={() => select(entry, true)}
                  className="size-4 shrink-0 accent-primary"
                />
                <div
                  className={
                    grid ? "ml-auto" : "col-start-3 row-start-1 lg:col-start-6"
                  }
                >
                  <EntryMenu
                    entry={entry}
                    open={menu === key}
                    setOpen={(open) => setMenu(open ? key : null)}
                    onOpen={() => open(entry)}
                    onDetails={() => details(entry)}
                  />
                </div>
              </div>
              {grid && (
                <div className="my-4 flex justify-center rounded-lg bg-background/60 py-6">
                  <ResourceIcon entry={entry} large />
                </div>
              )}
              <div
                className={cn(
                  "min-w-0",
                  grid
                    ? "space-y-2"
                    : "col-start-2 row-start-1 flex items-center gap-3",
                )}
              >
                {!grid && <ResourceIcon entry={entry} />}
                <div className="min-w-0">
                  <button
                    type="button"
                    className="block w-full truncate text-left text-sm font-medium hover:underline focus-visible:underline"
                    onClick={() => open(entry)}
                    disabled={
                      !canOpen(entry) &&
                      !canRead(entry) &&
                      entry.resourceType !== "TRASH"
                    }
                    title={entry.resource.name}
                  >
                    {entry.resource.name}
                  </button>
                  {entry.resourceType !== "TRASH" &&
                    entry.resource.effectiveRole !== "OWNER" && (
                      <span className="text-[11px] text-muted-foreground">
                        {entry.resource.effectiveRole === "EDITOR"
                          ? "Editor"
                          : "Viewer"}
                      </span>
                    )}
                  <p
                    className={cn(
                      "truncate text-xs text-muted-foreground",
                      !grid && "lg:hidden",
                    )}
                  >
                    {entryType(entry)} · {dateLabel(entryDate(entry))}
                    {entry.resourceType === "FILE"
                      ? ` · ${formatBytes(BigInt(entry.resource.sizeBytes))}`
                      : ""}
                  </p>
                </div>
              </div>
              {!grid && (
                <>
                  <span
                    className="hidden truncate text-xs text-muted-foreground lg:block"
                    title={entryType(entry)}
                  >
                    {entryType(entry)}
                  </span>
                  <time
                    className="hidden text-xs text-muted-foreground lg:block"
                    dateTime={entryDate(entry)}
                  >
                    {dateLabel(entryDate(entry))}
                  </time>
                  <span className="hidden text-xs text-muted-foreground lg:block">
                    {entry.resourceType === "FILE"
                      ? formatBytes(BigInt(entry.resource.sizeBytes))
                      : "—"}
                  </span>
                </>
              )}
            </div>
          );
        })}
      </div>
      {loading && (
        <div
          role="status"
          aria-label={`Loading ${title}`}
          className="space-y-3 py-4"
        >
          {[0, 1, 2].map((value) => (
            <Skeleton
              key={value}
              className={grid ? "h-24 w-full" : "h-12 w-full"}
            />
          ))}
        </div>
      )}
      {error !== undefined && (
        <ReadError
          error={error}
          retry={() => requestPage(page ? page.page + 1 : 0)}
        />
      )}
      {!loading && !error && entries.length === 0 && (
        <div className="rounded-xl border border-dashed bg-muted/20 px-5 py-10 text-center">
          <p className="font-medium">
            {feed.kind === "search"
              ? "No matching resources"
              : `No ${title.toLowerCase()} here`}
          </p>
          <p className="mt-2 text-sm text-muted-foreground">
            {feed.kind === "search"
              ? "Try another filename or clear the filters."
              : feed.kind === "shared"
                ? "Resources shared directly with you will appear here."
                : feed.kind.startsWith("trash")
                  ? "Trashed items will appear here. Restore and permanent deletion arrive in M4."
                  : "This location is empty. Uploads and new folders arrive in later milestones."}
          </p>
        </div>
      )}
      {page && page.page + 1 < page.totalPages && (
        <Button
          variant="outline"
          className="mt-4"
          disabled={loading || !!error}
          onClick={() => requestPage(page.page + 1)}
        >
          Load more {title.toLowerCase()}
        </Button>
      )}
    </section>
  );
}
