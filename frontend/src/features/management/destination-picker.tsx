"use client";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { getPage } from "@/features/explorer/api";
import type { ExplorerEntry, Page } from "@/features/explorer/contracts";
import { managementError } from "./model";

export function DestinationPicker({
  excludedId,
  currentParent,
  moving,
  onChoose,
}: {
  excludedId?: string;
  currentParent?: string | null;
  moving: boolean;
  onChoose: (id: string | null, name: string) => void;
}) {
  const [path, setPath] = useState<{ id: string; name: string }[]>([]);
  const current = path.at(-1);
  return (
    <section
      aria-label="Destination picker"
      className="space-y-3 rounded-lg border p-3"
    >
      <p className="break-words font-medium">
        Destination: My Drive{path.map((item) => ` / ${item.name}`).join("")}
      </p>
      {path.length > 0 && (
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => setPath((value) => value.slice(0, -1))}
        >
          Up one folder
        </Button>
      )}
      <FolderChoices
        key={current?.id ?? "root"}
        folder={current?.id}
        excludedId={excludedId}
        onOpen={(id, name) => setPath((value) => [...value, { id, name }])}
        onChoose={() =>
          onChoose(current?.id ?? null, current?.name ?? "My Drive")
        }
        disabled={moving && (current?.id ?? null) === currentParent}
      />
      <p className="text-xs text-muted-foreground">
        Only owned destinations are available. The server rechecks ownership,
        ancestry, cycles and names.
      </p>
    </section>
  );
}
function FolderChoices({
  folder,
  excludedId,
  disabled,
  onOpen,
  onChoose,
}: {
  folder?: string;
  excludedId?: string;
  disabled: boolean;
  onOpen: (id: string, name: string) => void;
  onChoose: () => void;
}) {
  const [pageNumber, setPageNumber] = useState(0);
  return (
    <FolderPage
      key={pageNumber}
      {...{
        folder,
        excludedId,
        disabled,
        onOpen,
        onChoose,
        pageNumber,
        setPageNumber,
      }}
    />
  );
}
function FolderPage({
  folder,
  excludedId,
  disabled,
  onOpen,
  onChoose,
  pageNumber,
  setPageNumber,
}: {
  folder?: string;
  excludedId?: string;
  disabled: boolean;
  pageNumber: number;
  onOpen: (id: string, name: string) => void;
  onChoose: () => void;
  setPageNumber: (page: number) => void;
}) {
  const [page, setPage] = useState<Page<ExplorerEntry>>();
  const [error, setError] = useState<string>();
  useEffect(() => {
    const controller = new AbortController();
    getPage({ kind: "folders", folder }, pageNumber, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) setPage(value);
      })
      .catch((error) => {
        if (!controller.signal.aborted) setError(managementError(error));
      });
    return () => controller.abort();
  }, [folder, pageNumber]);
  if (error) return <p role="alert">{error}</p>;
  if (!page) return <p role="status">Loading destinations…</p>;
  return (
    <>
      <ul
        aria-label="Destination folders"
        className="max-h-40 space-y-1 overflow-y-auto"
      >
        {page.content
          .filter(
            (entry) =>
              entry.resourceType === "FOLDER" &&
              entry.resource.effectiveRole === "OWNER" &&
              entry.resource.id !== excludedId &&
              entry.resource.allowedCapabilities.includes("LIST_CHILDREN"),
          )
          .map((entry) => (
            <li key={entry.resource.id}>
              <Button
                type="button"
                variant="ghost"
                className="w-full justify-start truncate"
                onClick={() => onOpen(entry.resource.id, entry.resource.name)}
              >
                Open {entry.resource.name}
              </Button>
            </li>
          ))}
      </ul>
      <div className="flex flex-wrap gap-2">
        <Button
          type="button"
          variant="secondary"
          disabled={disabled}
          onClick={onChoose}
        >
          Use this folder
        </Button>
        <Button
          type="button"
          variant="ghost"
          disabled={pageNumber === 0}
          onClick={() => setPageNumber(pageNumber - 1)}
        >
          Previous folders
        </Button>
        <Button
          type="button"
          variant="ghost"
          disabled={pageNumber + 1 >= page.totalPages}
          onClick={() => setPageNumber(pageNumber + 1)}
        >
          More folders
        </Button>
      </div>
    </>
  );
}
