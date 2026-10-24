"use client";
import { useEffect, useRef, useState } from "react";
import { Brand } from "@/components/brand";
import { Button } from "@/components/ui/button";
import { ThemeToggle } from "@/components/theme-toggle";
import { readResponse } from "@/lib/api/transport";
import { formatBytes } from "@/features/shell/storage";
import { validId, validSecret } from "./policy";
import type { PublicFile } from "./api";

export function SharePage({ id }: { id: string }) {
  const credential = useRef<string | null>(null);
  const credentialId = useRef(id);
  const [file, setFile] = useState<PublicFile>();
  const [secret, setSecret] = useState("");
  const [error, setError] = useState("");
  const [attempt, setAttempt] = useState(0);
  const [downloading, setDownloading] = useState(false);
  const downloadLock = useRef(false);
  useEffect(() => {
    if (credential.current === null || credentialId.current !== id) {
      credentialId.current = id;
      credential.current = window.location.hash.slice(1);
      // Replace, never push: the capability must not propagate through history,
      // auth redirects, outgoing links, server requests or query parameters.
      window.history.replaceState(null, "", window.location.pathname);
    }
    const controller = new AbortController();
    async function load() {
      try {
        if (!validId(id) || !validSecret(credential.current ?? ""))
          throw new Error();
        const result = await readResponse<PublicFile>(
          await fetch(`/api/v1/public/share-links/${id}`, {
            cache: "no-store",
            credentials: "omit",
            headers: { "X-VaultDrive-Share-Token": credential.current! },
            signal: AbortSignal.any([
              controller.signal,
              AbortSignal.timeout(15000),
            ]),
          }),
        );
        if (!controller.signal.aborted) {
          setFile(result);
          setSecret(credential.current!);
          setError("");
        }
      } catch {
        if (!controller.signal.aborted) {
          setFile(undefined);
          setSecret("");
          setError(
            "This share link is unavailable. It may be invalid, expired, revoked, or no longer accessible. If the service is busy, wait before checking again.",
          );
        }
      }
    }
    void load();
    const changedFragment = () => {
      credential.current = null;
      setFile(undefined);
      setSecret("");
      setError("");
      setAttempt((value) => value + 1);
    };
    window.addEventListener("hashchange", changedFragment);
    return () => {
      controller.abort();
      window.removeEventListener("hashchange", changedFragment);
    };
  }, [id, attempt]);
  return (
    <div className="min-h-dvh bg-background text-foreground">
      <header className="flex items-center justify-between border-b p-5">
        <Brand />
        <ThemeToggle />
      </header>
      <main className="mx-auto max-w-lg space-y-5 px-5 py-12">
        <h1 className="text-2xl font-semibold">Shared file</h1>
        <p className="text-sm text-muted-foreground">
          Viewer link · Anyone holding the secret can read and download this
          file until access ends. It does not grant workspace access.
        </p>
        {error ? (
          <p role="alert">{error}</p>
        ) : file ? (
          <>
            <h2 className="break-words text-xl font-medium">{file.name}</h2>
            <p className="text-sm">
              {formatBytes(BigInt(file.sizeBytes))} ·{" "}
              {file.contentType ?? "File"}
            </p>
            <Button
              disabled={downloading}
              onClick={async () => {
                if (downloadLock.current) return;
                downloadLock.current = true;
                setDownloading(true);
                try {
                  await readResponse<void>(
                    await fetch(`/share/${id}/download`, {
                      method: "POST",
                      cache: "no-store",
                      credentials: "same-origin",
                      headers: {
                        "Content-Type": "application/json",
                        "X-VaultDrive-Client": "1",
                      },
                      body: JSON.stringify({ secret }),
                      signal: AbortSignal.timeout(15000),
                    }),
                  );
                  // Native navigation owns the attachment stream; router.push
                  // would treat the download as an RSC page transition.
                  // eslint-disable-next-line @next/next/no-location-assign-relative-destination
                  window.location.assign(`/share/${id}/download`);
                } catch {
                  setError(
                    "The download could not start. Check link access and try again. No download was automatically retried.",
                  );
                } finally {
                  downloadLock.current = false;
                  setDownloading(false);
                }
              }}
            >
              {downloading ? "Preparing download…" : "Download shared file"}
            </Button>
            <p className="text-xs text-muted-foreground">
              Each download checks current access. Check the browser download
              panel for completion or interruptions. A download already admitted
              may finish after revocation.
            </p>
          </>
        ) : (
          <p role="status">Loading shared file…</p>
        )}
        <Button
          variant="outline"
          onClick={() => {
            setFile(undefined);
            setError("");
            setAttempt((value) => value + 1);
          }}
        >
          Check link access
        </Button>
        <p className="text-xs text-muted-foreground">
          The secret stays in this page’s memory. Reloading requires reopening
          the original link.
        </p>
      </main>
    </div>
  );
}
