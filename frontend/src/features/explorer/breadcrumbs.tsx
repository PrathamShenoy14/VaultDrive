"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { getMetadata } from "./api";
import { folderHref, type FolderResource } from "./contracts";
import { ApiError } from "@/lib/api/transport";

export function Breadcrumbs({
  folder,
  shared,
}: {
  folder: FolderResource;
  shared: boolean;
}) {
  const [parents, setParents] = useState<FolderResource[]>([]);
  const [incomplete, setIncomplete] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    async function load() {
      const chain: FolderResource[] = [];
      const visited = new Set([folder.id]);
      let parent = folder.parentFolderId;
      try {
        while (parent && chain.length < 256) {
          if (visited.has(parent)) throw new Error("Hierarchy unavailable");
          visited.add(parent);
          const item = await getMetadata("FOLDER", parent, controller.signal);
          chain.unshift(item);
          parent = item.parentFolderId;
        }
        if (parent) throw new Error("Hierarchy unavailable");
      } catch (error) {
        if (!controller.signal.aborted) {
          if (error instanceof ApiError && error.status === 401)
            chain.length = 0;
          setIncomplete(true);
        }
      }
      if (!controller.signal.aborted) setParents(chain);
    }
    void load();
    return () => controller.abort();
  }, [folder]);
  return (
    <nav aria-label="Folder breadcrumbs" className="mb-5 text-sm">
      <ol className="flex flex-wrap items-center gap-x-2 gap-y-2 break-all">
        <li>
          <Link
            href={shared ? "/shared" : "/drive"}
            prefetch={false}
            className="text-muted-foreground hover:underline"
          >
            {shared ? "Shared with me" : "My Drive"}
          </Link>
        </li>
        {[...parents, folder].map((item, index, all) => (
          <li key={item.id} className="flex min-w-0 items-center gap-2">
            <span aria-hidden="true" className="text-muted-foreground">
              /
            </span>
            {index === all.length - 1 ? (
              <span aria-current="page">{item.name}</span>
            ) : (
              <Link
                prefetch={false}
                href={folderHref(item.id, shared)}
                className="hover:underline"
              >
                {item.name}
              </Link>
            )}
          </li>
        ))}
      </ol>
      {shared && (
        <p className="mt-2 text-xs text-muted-foreground">
          Shared entry points. Only accessible ancestors are shown.
        </p>
      )}
      {incomplete && (
        <p role="status" className="mt-2 text-xs text-muted-foreground">
          Some ancestors are unavailable. Refresh to check access again.
        </p>
      )}
    </nav>
  );
}
