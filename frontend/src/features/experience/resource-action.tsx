"use client";
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { DetailsPanel } from "@/features/explorer/resource-ui";
import {
  folderHref,
  type ExplorerEntry,
  type FileResource,
  type FolderResource,
} from "@/features/explorer/contracts";
import { errorMessage } from "@/lib/api/transport";
import { experienceRequest } from "./api";
export function ResourceAction({
  type,
  id,
  denied,
  navigated,
}: {
  type: "FILE" | "FOLDER";
  id: string;
  denied?: () => void;
  navigated?: () => void;
}) {
  const router = useRouter();
  const active = useRef<AbortController | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const [entry, setEntry] = useState<ExplorerEntry | null>(null);
  useEffect(() => () => active.current?.abort(), []);
  async function open() {
    if (active.current) return;
    const controller = new AbortController();
    active.current = controller;
    setBusy(true);
    setError(undefined);
    setEntry(null);
    try {
      if (!/^[0-9a-f-]{36}$/i.test(id))
        throw new Error("Invalid resource identity");
      const resource = await experienceRequest<FileResource | FolderResource>(
        `${type === "FILE" ? "files" : "folders"}/${id}`,
        controller.signal,
      );
      if (controller.signal.aborted) return;
      if (!resource.allowedCapabilities.includes("READ_METADATA"))
        throw new Error("Unavailable");
      if (
        type === "FOLDER" &&
        resource.allowedCapabilities.includes("LIST_CHILDREN")
      ) {
        router.push(folderHref(id, resource.effectiveRole !== "OWNER"));
        navigated?.();
      } else
        setEntry(
          type === "FILE"
            ? { resourceType: "FILE", resource: resource as FileResource }
            : { resourceType: "FOLDER", resource: resource as FolderResource },
        );
    } catch (error) {
      if (!controller.signal.aborted) {
        setError(error);
        denied?.();
      }
    } finally {
      if (!controller.signal.aborted) setBusy(false);
      active.current = null;
    }
  }
  return (
    <div className="mt-2">
      <Button variant="outline" size="sm" disabled={busy} onClick={open}>
        {busy ? "Checking access…" : "Open resource"}
      </Button>
      {error !== undefined && (
        <p role="alert" className="mt-2 text-sm">
          {errorMessage(error)} The resource may have been removed or access
          changed.
        </p>
      )}
      <DetailsPanel entry={entry} close={() => setEntry(null)} />
    </div>
  );
}
