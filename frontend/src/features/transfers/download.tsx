"use client";
import { useState } from "react";
import { Download } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { UUID } from "./contracts";

export const downloadHref = (id: string) =>
  UUID.test(id) ? `/api/v1/files/${encodeURIComponent(id)}/download` : "#";
export function DownloadAction({ id }: { id: string }) {
  const [requested, setRequested] = useState(false);
  return (
    <>
      <a
        className={buttonVariants({ variant: "ghost", size: "sm" })}
        href={downloadHref(id)}
        target="_blank"
        rel="noopener noreferrer"
        onClick={() => setRequested(true)}
      >
        <Download aria-hidden="true" />
        Download
      </a>
      {requested && (
        <span role="status" className="text-xs text-muted-foreground">
          Check your browser downloads for completion or interruption. Retry
          explicitly if needed; errors open in a separate tab.
        </span>
      )}
    </>
  );
}
