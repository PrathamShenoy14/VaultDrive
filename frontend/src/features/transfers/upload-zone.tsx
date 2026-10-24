"use client";
import { useRef, useState } from "react";
import { Upload } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useTransfers } from "./provider";

export function UploadZone({
  folderId,
  children,
}: {
  folderId: string | null;
  children: React.ReactNode;
}) {
  const manager = useTransfers();
  const picker = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState<string>();
  function add(files: File[]) {
    setError(manager?.add(files, folderId));
  }
  return (
    <div
      onDragOver={(event) => {
        if (event.dataTransfer.types.includes("Files")) {
          event.preventDefault();
          event.dataTransfer.dropEffect = "copy";
          setDragging(true);
        }
      }}
      onDragLeave={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null))
          setDragging(false);
      }}
      onDrop={(event) => {
        event.preventDefault();
        setDragging(false);
        const items = Array.from(event.dataTransfer.items ?? []);
        if (items.some((item) => item.webkitGetAsEntry?.()?.isDirectory)) {
          setError(
            "Choose files rather than folders. Folder uploads are not supported.",
          );
          return;
        }
        add(Array.from(event.dataTransfer.files));
      }}
      className={
        dragging
          ? "rounded-xl outline-2 outline-offset-4 outline-primary"
          : undefined
      }
    >
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <Button
          className="transition-none hover:bg-primary"
          disabled={!manager}
          onClick={() => picker.current?.click()}
        >
          <Upload aria-hidden="true" />
          Upload files
        </Button>
        <input
          ref={picker}
          type="file"
          multiple
          className="sr-only"
          tabIndex={-1}
          aria-label="Choose files to upload"
          onChange={(event) => {
            add(Array.from(event.target.files ?? []));
            event.target.value = "";
          }}
        />
        <p className="text-xs text-muted-foreground">
          {dragging
            ? "Drop files to queue uploads"
            : "Or drop files here · up to 5 GiB each"}
        </p>
      </div>
      {error && (
        <p role="alert" className="mb-3 text-sm text-destructive">
          {error}
        </p>
      )}
      {children}
    </div>
  );
}
