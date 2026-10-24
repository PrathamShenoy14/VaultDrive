"use client";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { formatBytes } from "@/features/shell/storage";
import { canRecoverCompletion, type Transfer } from "./contracts";
import { UploadManager } from "./manager";
import { useTransferList } from "./provider";

function TransferRow({
  transfer: t,
  manager,
}: {
  transfer: Transfer;
  manager: UploadManager;
}) {
  const [error, setError] = useState<string>();
  const done = t.state === "completed" || t.state === "cancelled";
  const canRecover =
    !!t.emptyId ||
    canRecoverCompletion(t.session) ||
    t.session?.state === "ABANDONMENT_DECIDED";
  const canResume =
    (!t.emptyId || t.empty?.state === "RESERVED") &&
    (!t.session || ["RESERVED", "OPEN"].includes(t.session.state)) &&
    !["initiate", "complete", "cancel"].includes(t.dispatch ?? "");
  const canCancel =
    !done &&
    !(t.size === 0 && (t.dispatch || t.emptyId)) &&
    t.dispatch !== "complete" &&
    !t.session?.completion;
  const [waiting, setWaiting] = useState(false);
  useEffect(() => {
    if (!t.retryAt || t.retryAt <= Date.now()) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setWaiting(true);
    const timer = setTimeout(() => setWaiting(false), t.retryAt - Date.now());
    return () => clearTimeout(timer);
  }, [t.retryAt]);
  return (
    <li className="space-y-2 border-t py-3 first:border-0">
      <div className="flex flex-wrap items-start justify-between gap-2 text-sm">
        <span className="min-w-0 break-all font-medium">{t.name}</span>
        <span className="capitalize">{t.state}</span>
      </div>
      <progress
        aria-label={`Upload progress for ${t.name}`}
        max={Math.max(1, t.size)}
        value={t.size === 0 && t.state === "completed" ? 1 : t.bytes}
        className="h-2 w-full accent-primary"
      />
      <p className="text-xs text-muted-foreground">
        {formatBytes(BigInt(t.bytes))} / {formatBytes(BigInt(t.size))}
        {t.state !== "completed" && t.bytes === t.size && t.size > 0
          ? " · Waiting for publication"
          : ""}
      </p>
      <p
        className="break-words text-xs"
        role={
          t.state === "failed" || t.state === "blocked" ? "alert" : undefined
        }
      >
        {t.message}
      </p>
      {(t.sessionId || t.emptyId) && (
        <p className="break-all text-xs text-muted-foreground">
          {t.emptyId ? "Empty creation" : "Upload session"}:{" "}
          <code>{t.emptyId ?? t.sessionId}</code>
        </p>
      )}
      {t.dispatch && !t.sessionId && !t.emptyId && (
        <p className="break-all text-xs text-muted-foreground">
          Admission key: <code>{t.key}</code>
        </p>
      )}
      {error && (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      )}
      {t.authRequired && (
        <a href="/login" className="text-sm underline">
          Sign in to inspect this transfer
        </a>
      )}
      <div className="flex flex-wrap gap-2">
        {!done && (
          <>
            <Button
              variant="outline"
              size="sm"
              disabled={t.busy || waiting}
              onClick={() => manager.act(t.id, "check")}
            >
              Check status
            </Button>
            {canRecover && (
              <Button
                variant="outline"
                size="sm"
                disabled={t.busy || waiting}
                onClick={() => manager.act(t.id, "recover")}
              >
                Recover
              </Button>
            )}
            {canResume && (
              <>
                {t.size > 0 && !t.hasFile && (
                  <label className="min-w-0 text-xs">
                    Original file
                    <input
                      className="mt-1 block max-w-full"
                      aria-label={`Reselect original file for ${t.name}`}
                      type="file"
                      disabled={t.busy}
                      onChange={(event) => {
                        const file = event.target.files?.[0];
                        if (file) setError(manager.attach(t.id, file));
                        event.target.value = "";
                      }}
                    />
                  </label>
                )}
                <Button
                  variant="outline"
                  size="sm"
                  disabled={t.busy || waiting || (t.size > 0 && !t.hasFile)}
                  onClick={() => manager.act(t.id, "run")}
                >
                  {!t.sessionId
                    ? "Retry admission with original key"
                    : "Resume"}
                </Button>
              </>
            )}
            {canCancel && (
              <Button
                variant="ghost"
                size="sm"
                disabled={t.state === "cancelling" || t.dispatch === "cancel"}
                onClick={() => manager.cancel(t.id)}
              >
                Cancel
              </Button>
            )}
          </>
        )}
        {(done ||
          (t.state === "failed" &&
            !t.dispatch &&
            !t.sessionId &&
            !t.emptyId)) && (
          <Button
            variant="ghost"
            size="sm"
            disabled={t.busy}
            onClick={() => manager.dismiss(t.id)}
          >
            Dismiss
          </Button>
        )}
      </div>
    </li>
  );
}
export function TransferPanel({ manager }: { manager: UploadManager }) {
  const transfers = useTransferList(manager);
  const [open, setOpen] = useState(true);
  if (!transfers.length) return null;
  const total = transfers.reduce((sum, t) => sum + t.size, 0),
    sent = transfers.reduce((sum, t) => sum + t.bytes, 0);
  const completed = transfers.filter((t) => t.state === "completed").length;
  return (
    <section
      aria-label="Transfers"
      className="fixed inset-x-3 bottom-3 z-30 max-w-lg rounded-2xl border bg-card p-4 shadow-xl sm:right-5 sm:left-auto sm:w-[28rem]"
    >
      <div className="flex items-center justify-between gap-2">
        <h2 className="font-semibold">Transfers</h2>
        <Button
          variant="ghost"
          size="sm"
          aria-expanded={open}
          onClick={() => setOpen(!open)}
        >
          {open ? "Minimize" : "Expand"}
        </Button>
      </div>
      <p role="status" className="my-2 text-xs text-muted-foreground">
        {completed} of {transfers.length} published ·{" "}
        {formatBytes(BigInt(sent))} / {formatBytes(BigInt(total))} sent
      </p>
      <progress
        aria-label="Overall upload progress"
        max={Math.max(1, total)}
        value={total === 0 && completed === transfers.length ? 1 : sent}
        className="h-2 w-full accent-primary"
      />
      {open && (
        <>
          <p className="my-2 text-xs text-muted-foreground">
            Two files at a time. Bytes sent are provisional until READY. Reload
            requires the original file. Cancellation does not promise storage
            reclamation.
          </p>
          <ul className="max-h-[45svh] overflow-y-auto overscroll-contain pr-1">
            {transfers.map((t) => (
              <TransferRow key={t.id} transfer={t} manager={manager} />
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
