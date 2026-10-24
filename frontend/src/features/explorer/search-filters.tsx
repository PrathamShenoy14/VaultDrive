"use client";
import { useRouter, useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { searchFilters } from "./contracts";

export function SearchFilters() {
  const router = useRouter();
  const params = useSearchParams();
  return (
    <details className="mb-6 rounded-xl border bg-muted/20 p-4">
      <summary className="cursor-pointer text-sm font-medium">
        Search filters
      </summary>
      <form
        key={params.toString()}
        className="mt-4 grid gap-4 sm:grid-cols-2 xl:grid-cols-3"
        onSubmit={(event) => {
          event.preventDefault();
          const data = new FormData(event.currentTarget);
          const next = new URLSearchParams();
          next.set("q", params.get("q") ?? "");
          for (const key of searchFilters) {
            const value = String(data.get(key) ?? "").trim();
            if (value) next.set(key, value);
          }
          router.push(`/search?${next}`);
        }}
      >
        <label className="space-y-1 text-sm">
          Type
          <select
            name="resourceType"
            defaultValue={params.get("resourceType") ?? ""}
            className="block h-9 w-full rounded-md border bg-background px-2"
          >
            <option value="">Files and folders</option>
            <option value="FILE">Files</option>
            <option value="FOLDER">Folders</option>
          </select>
        </label>
        <label className="space-y-1 text-sm">
          Location
          <select
            name="scope"
            defaultValue={params.get("scope") ?? "ALL"}
            className="block h-9 w-full rounded-md border bg-background px-2"
          >
            <option value="ALL">All accessible resources</option>
            <option value="OWNED">Owned by me</option>
            <option value="SHARED">Shared with me</option>
          </select>
        </label>
        <label className="space-y-1 text-sm">
          MIME type
          <Input
            name="mimeType"
            defaultValue={params.get("mimeType") ?? ""}
            placeholder="application/pdf"
            maxLength={255}
          />
        </label>
        {(
          [
            ["minSizeBytes", "Minimum size (bytes)"],
            ["maxSizeBytes", "Maximum size (bytes)"],
          ] as const
        ).map(([key, label]) => (
          <label key={key} className="space-y-1 text-sm">
            {label}
            <Input
              name={key}
              inputMode="numeric"
              pattern="[0-9]*"
              defaultValue={params.get(key) ?? ""}
              placeholder="0"
            />
          </label>
        ))}
        {(
          [
            ["createdFrom", "Created from (UTC)"],
            ["createdTo", "Created to (UTC)"],
            ["modifiedFrom", "Modified from (UTC)"],
            ["modifiedTo", "Modified to (UTC)"],
          ] as const
        ).map(([key, label]) => (
          <label key={key} className="space-y-1 text-sm">
            {label}
            <Input
              name={key}
              defaultValue={params.get(key) ?? ""}
              placeholder="2026-10-24T00:00:00Z"
            />
          </label>
        ))}
        <p className="text-xs text-muted-foreground sm:col-span-2 xl:col-span-3">
          Filters combine with AND. MIME and size apply to files; leave them
          empty for folders. Dates are inclusive ISO 8601 instants with a
          timezone.
        </p>
        <div className="flex gap-2">
          <Button type="submit" size="sm">
            Apply filters
          </Button>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() =>
              router.push(
                `/search?${new URLSearchParams({ q: params.get("q") ?? "" })}`,
              )
            }
          >
            Reset filters
          </Button>
        </div>
      </form>
    </details>
  );
}
