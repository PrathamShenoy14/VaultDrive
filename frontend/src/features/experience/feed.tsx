"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { errorMessage } from "@/lib/api/transport";
import { experienceRequest } from "./api";
export type HistoryPage<T> = {
  content: T[];
  page: number;
  totalPages: number | bigint;
  totalElements: number | bigint;
};
export function useExperienceRead<T>(path: string, revision = 0) {
  const [value, setValue] = useState<T>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    void experienceRequest<T>(path, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) {
          setValue(value);
          setError(undefined);
        }
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setValue(undefined);
          setError(error);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [path, revision, attempt]);
  return {
    value,
    error,
    loading,
    reload: () => {
      setValue(undefined);
      setLoading(true);
      setError(undefined);
      setAttempt((n) => n + 1);
    },
  };
}
export function ReadState({
  loading,
  error,
  retry,
}: {
  loading: boolean;
  error: unknown;
  retry: () => void;
}) {
  return (
    <>
      {loading && (
        <p role="status" className="py-5 text-sm text-muted-foreground">
          Loading…
        </p>
      )}
      {error !== undefined && (
        <div role="alert" className="space-y-3 py-4 text-sm">
          <p>{errorMessage(error)}</p>
          <Button variant="outline" onClick={retry}>
            Retry
          </Button>
        </div>
      )}
    </>
  );
}
export function Pagination({
  page,
  href,
}: {
  page: HistoryPage<unknown>;
  href: (index: number) => string;
}) {
  return (
    <nav
      aria-label="History pagination"
      className="mt-6 flex flex-wrap items-center gap-4 text-sm"
    >
      {page.page > 0 && (
        <Link className="underline" href={href(page.page - 1)}>
          Previous page
        </Link>
      )}
      <span>
        Page {page.page + 1} · {page.totalElements.toString()}{" "}
        {BigInt(page.totalElements) === 1n ? "event" : "events"}
      </span>
      {BigInt(page.page + 1) < BigInt(page.totalPages) && (
        <Link className="underline" href={href(page.page + 1)}>
          Next page
        </Link>
      )}
    </nav>
  );
}
export function historyQuery(params: URLSearchParams, keys: string[]) {
  const result = new URLSearchParams();
  for (const key of keys) {
    const value = params.get(key);
    if (value) result.set(key, value);
  }
  const page = params.get("page") ?? "0";
  result.set(
    "page",
    /^\d{1,10}$/.test(page) && BigInt(page) <= 2147483647n ? page : "0",
  );
  result.set("size", "20");
  return result;
}
