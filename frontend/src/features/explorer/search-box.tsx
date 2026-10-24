"use client";
import { useEffect, useRef, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Search, X } from "lucide-react";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { searchFilters } from "./contracts";

export function SearchBox() {
  const params = useSearchParams();
  const pathname = usePathname();
  const query = pathname === "/search" ? (params.get("q") ?? "") : "";
  const router = useRouter();
  const [previous, setPrevious] = useState(`${pathname}:${query}`);
  const [value, setValue] = useState(query);
  const dirty = useRef(false);
  const input = useRef<HTMLInputElement>(null);
  // Reconcile external back/forward navigation without replacing the focused input.
  if (previous !== `${pathname}:${query}`) {
    setPrevious(`${pathname}:${query}`);
    setValue(query);
  }
  const submit = (next: string) => {
    const query = new URLSearchParams();
    if (next.trim()) {
      query.set("q", next.trim());
      // Keep filters when editing an existing search, never folder identifiers.
      for (const key of [...searchFilters, "view"]) {
        const value = params.get(key);
        if (value) query.set(key, value);
      }
    }
    const href = next.trim() ? `/search?${query}` : "/drive";
    router.push(href);
  };
  useEffect(() => {
    dirty.current = false;
  }, [pathname, query]);
  useEffect(() => {
    if (!dirty.current) return;
    const timeout = setTimeout(() => {
      if (dirty.current) {
        dirty.current = false;
        submit(value);
      }
    }, 400);
    return () => clearTimeout(timeout);
    // URL params are read by the current render; unrelated shell renders must not restart debounce.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);
  return (
    <form
      role="search"
      className="relative max-w-2xl min-w-0 flex-1"
      onSubmit={(event) => {
        event.preventDefault();
        dirty.current = false;
        submit(value);
      }}
    >
      <Search
        aria-hidden="true"
        className="pointer-events-none absolute top-3 left-4 size-5 text-muted-foreground"
      />
      <Input
        ref={input}
        aria-label="Search in VaultDrive"
        placeholder="Search in VaultDrive"
        value={value}
        onChange={(event) => {
          dirty.current = true;
          setValue(event.target.value);
        }}
        className="h-11 rounded-full border-transparent bg-secondary/70 pr-12 pl-12 shadow-none"
      />
      {value && (
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label="Clear search"
          className="absolute top-1.5 right-2 rounded-full"
          onClick={() => {
            dirty.current = false;
            setValue("");
            input.current?.focus();
            submit("");
          }}
        >
          <X aria-hidden="true" />
        </Button>
      )}
    </form>
  );
}
